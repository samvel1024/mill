package mill.contrib.bsp
import sbt.testing._
import java.util.{Calendar, Collections}
import java.util.concurrent.CompletableFuture

import mill.scalalib.Lib.discoverTests
import ch.epfl.scala.bsp4j._
import mill.{scalalib, _}
import mill.api.{Loose, Result, Strict}
import mill.contrib.bsp.ModuleUtils._
import mill.eval.{Evaluator}
import mill.scalalib._
import mill.scalalib.api.CompilationResult
import mill.scalalib.api.ZincWorkerApi

import scala.collection.mutable.Map
import mill.api.Result.{Failing, Success}

import scala.collection.JavaConverters._
import mill.modules.Jvm
import mill.util.{Ctx, PrintLogger}
import mill.define.{Discover, ExternalModule, Target, Task}


class MillBuildServer(evaluator: Evaluator,
                      _bspVersion: String,
                      serverVersion:String,
                      languages: List[String]) extends ExternalModule with BuildServer with ScalaBuildServer  {

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover: Discover[MillBuildServer.this.type] = Discover[this.type]

  val bspVersion: String = _bspVersion
  val supportedLanguages: List[String] = languages
  val millServerVersion: String = serverVersion
  var cancelator: () => Unit = () => ()
  var millEvaluator: Evaluator = evaluator
  var millModules: Seq[JavaModule] = getMillModules(millEvaluator)
  var client: BuildClient = _
  var moduleToTargetId: Predef.Map[JavaModule, BuildTargetIdentifier] = ModuleUtils.getModuleTargetIdMap(millModules)
  var targetIdToModule: Predef.Map[BuildTargetIdentifier, JavaModule] = targetToModule(moduleToTargetId)
  var moduleToTarget: Predef.Map[JavaModule, BuildTarget] =
                                  ModuleUtils.millModulesToBspTargets(millModules, evaluator, List("scala", "java"))


  var clientInitialized = false

  val ctx: Ctx.Log with Ctx.Home = new Ctx.Log with Ctx.Home {
    val log = mill.util.DummyLogger
    val home = os.pwd
  }

  override def onConnectWithClient(server: BuildClient): Unit =
    client = server

  override def buildInitialize(params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] = {

    val capabilities = new BuildServerCapabilities
    capabilities.setCompileProvider(new CompileProvider(List("java", "scala").asJava))
    capabilities.setRunProvider(new RunProvider(List("java", "scala").asJava))
    capabilities.setTestProvider(new TestProvider(List("java", "scala").asJava))
    capabilities.setDependencySourcesProvider(true)
    capabilities.setInverseSourcesProvider(true)
    capabilities.setResourcesProvider(true)
    capabilities.setBuildTargetChangedProvider(false) //TODO: for now it's false, but will try to support this later
    val future = new CompletableFuture[InitializeBuildResult]()
    future.complete(new InitializeBuildResult("mill-bsp", millServerVersion, bspVersion, capabilities))
    future
  }

  override def onBuildInitialized(): Unit = {
    clientInitialized = true
  }

  override def buildShutdown(): CompletableFuture[Object] = {
    clientInitialized match {
      case true => val future = new CompletableFuture[AnyRef]()
        future.complete("shut down this server")
        future
      case false => throw new Error("Can not send any other request before the initialize request")
    }

  }

  override def onBuildExit(): Unit = {
    cancelator()
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] = {
    recomputeTargets()
    val future = new CompletableFuture[WorkspaceBuildTargetsResult]()
    val result = new WorkspaceBuildTargetsResult(moduleToTarget.values.toList.asJava)
    future.complete(result)
    future
  }

  private[this] def getSourceFiles(sources: Seq[os.Path]): Iterable[os.Path] = {
    var files = Seq.empty[os.Path]

    for (source <- sources) {
      if (os.exists(source)) (if (os.isDir(source)) os.walk(source) else Seq(source))
        .foreach(path => if (os.isFile(path) && List("scala", "java").contains(path.ext) &&
          !path.last.startsWith(".")) {
          files ++= Seq(path)
        })
    }

    files
  }

  override def buildTargetSources(sourcesParams: SourcesParams): CompletableFuture[SourcesResult] = {

    def computeSourcesResult: SourcesResult = {
      var items = List[SourcesItem]()

      for (targetId <- sourcesParams.getTargets.asScala) {
        var itemSources = List[SourceItem]()

        val sources = evaluateInformativeTask(evaluator, targetIdToModule(targetId).sources, Agg.empty[PathRef]).
                      map(pathRef => pathRef.path).toSeq
        val generatedSources = evaluateInformativeTask(evaluator,
                                                        targetIdToModule(targetId).generatedSources,
                                                        Agg.empty[PathRef]).
                                                        map(pathRef => pathRef.path).toSeq

        for (file <- getSourceFiles(sources)) {
          itemSources ++= List(new SourceItem(file.toNIO.toAbsolutePath.toUri.toString, SourceItemKind.FILE, false))
        }

        for (file <- getSourceFiles(generatedSources)) {
          itemSources ++= List(new SourceItem(file.toNIO.toAbsolutePath.toUri.toString, SourceItemKind.FILE, true))
        }

        items ++= List(new SourcesItem(targetId, itemSources.asJava))
      }

      new SourcesResult(items.asJava)
    }

    val future = new CompletableFuture[SourcesResult]()
    future.complete(computeSourcesResult)
    future
  }

  override def buildTargetInverseSources(inverseSourcesParams: InverseSourcesParams):
  CompletableFuture[InverseSourcesResult] = {

    def getInverseSourcesResult: InverseSourcesResult = {
      val textDocument = inverseSourcesParams.getTextDocument

      val targets = (for (targetId <- targetIdToModule.keys
                          if buildTargetSources(new SourcesParams(Collections.singletonList(targetId))).
                            get.getItems.asScala.head.getSources.asScala.
                            exists(item => item.getUri.equals(textDocument.getUri)))
        yield targetId).toList.asJava
      new InverseSourcesResult(targets)
    }

    val future = new CompletableFuture[InverseSourcesResult]()
    future.complete(getInverseSourcesResult)
    future
  }

  override def buildTargetDependencySources(dependencySourcesParams: DependencySourcesParams):
  CompletableFuture[DependencySourcesResult] = {
    def getDependencySources: DependencySourcesResult = {
      var items = List[DependencySourcesItem]()

      for (targetId <- dependencySourcesParams.getTargets.asScala) {
        val millModule = targetIdToModule(targetId)
        var sources = evaluateInformativeTask(evaluator,
                                              millModule.resolveDeps(millModule.transitiveIvyDeps),
                                              Agg.empty[PathRef]) ++
                      evaluateInformativeTask(evaluator,
                                              millModule.resolveDeps(millModule.compileIvyDeps),
                                              Agg.empty[PathRef])
        millModule match {
          case m: ScalaModule => sources ++= evaluateInformativeTask(evaluator,
                                    millModule.resolveDeps(millModule.asInstanceOf[ScalaModule].scalaLibraryIvyDeps),
                                    Agg.empty[PathRef])
          case m: JavaModule => sources ++= List()
        }
        items ++= List(new DependencySourcesItem(targetId, sources.
                                                    map(pathRef => pathRef.path.toNIO.toAbsolutePath.toUri.toString).
                                                    toList.asJava))
      }

      new DependencySourcesResult(items.asJava)
    }

    val future = new CompletableFuture[DependencySourcesResult]()
    future.complete(getDependencySources)
    future
  }

  override def buildTargetResources(resourcesParams: ResourcesParams): CompletableFuture[ResourcesResult] = {

    def getResources: ResourcesResult = {
      var items = List[ResourcesItem]()

      for (targetId <- resourcesParams.getTargets.asScala) {
        val millModule = targetIdToModule(targetId)
        val resources = evaluateInformativeTask(evaluator, millModule.resources, Agg.empty[PathRef]).
                        flatMap(pathRef => os.walk(pathRef.path)).
                        map(path => path.toNIO.toAbsolutePath.toUri.toString).
                        toList.asJava
        items ++= List(new ResourcesItem(targetId, resources))
      }

      new ResourcesResult(items.asJava)
    }

    val future = new CompletableFuture[ResourcesResult]()
    future.complete(getResources)
    future
  }

  //TODO: send task notifications - start, progress and finish
  //TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  //TODO: Send notification if compilation fails
  override def buildTargetCompile(compileParams: CompileParams): CompletableFuture[CompileResult] = {

    def getCompileResult: CompileResult = {

      var numFailures = 0
      var compileTime = 0
      for (targetId <- compileParams.getTargets.asScala) {
        if (moduleToTarget(targetIdToModule(targetId)).getCapabilities.getCanCompile) {
          var millModule = targetIdToModule(targetId)
          //millModule.javacOptions = compileParams.getArguments.asScala
          val compileTask = millModule.compile
          // send notification to client that compilation of this target started
          val taskStartParams = new TaskStartParams(new TaskId(compileTask.hashCode().toString))
          taskStartParams.setEventTime(System.currentTimeMillis())
          taskStartParams.setMessage("Compiling target: " + targetId)
          taskStartParams.setDataKind("compile-task")
          taskStartParams.setData(new CompileTask(targetId))
          client.onBuildTaskStart(taskStartParams)

          val result = millEvaluator.evaluate(Strict.Agg(compileTask))
          val endTime = System.currentTimeMillis()
          compileTime += result.timings.map(timingTuple => timingTuple._2).sum
          var statusCode = StatusCode.OK

//          result.results(compileTask) match {
//            case r: Failing[CompilationResult] =>
//              statusCode = StatusCode.ERROR
//              numFailures += result.failing.keyCount
//            case default =>
//          }
          if (result.failing.keyCount > 0) {
            statusCode = StatusCode.ERROR
            numFailures += result.failing.keyCount
          }

          // send notification to client that compilation of this target ended => compilation report
          val taskFinishParams = new TaskFinishParams(new TaskId(compileTask.hashCode().toString), statusCode)
          taskFinishParams.setEventTime(endTime)
          taskFinishParams.setMessage("Finished compiling target: " +
                                              moduleToTarget(targetIdToModule(targetId)).getDisplayName)
          taskFinishParams.setDataKind("compile-report")
          val compileReport = new CompileReport(targetId, numFailures, 0)
          compileReport.setOriginId(compileParams.getOriginId)
          compileReport.setTime(compileTime.toLong)
          taskFinishParams.setData(compileReport)
          client.onBuildTaskFinish(taskFinishParams)
        }
      }

      var overallStatusCode = StatusCode.OK
      if (numFailures > 0) {
        overallStatusCode = StatusCode.ERROR
      }
      val compileResult = new CompileResult(overallStatusCode)
      compileResult.setOriginId(compileParams.getOriginId)
      compileResult //TODO: See what form IntelliJ expects data about products of compilation in order to set data field
      }

    val future = new CompletableFuture[CompileResult]()
    future.complete(getCompileResult)
    future
  }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] = {
    def getRunResult: RunResult = {
        val module = targetIdToModule(runParams.getTarget)
        val args = runParams.getArguments
//        val runResult = runParams.getData() match {
//          case d: ScalaMainClass => millEvaluator.evaluate(Strict.Agg(module.runMain(d.getClass, d.getArguments.asScala)))
//          case default => millEvaluator.evaluate(Strict.Agg(module.run(args.asScala.mkString(" "))))
//        }
        val runResult = millEvaluator.evaluate(Strict.Agg(module.run(args.asScala.mkString(" "))))
        if (runResult.failing.keyCount > 0) {
          new RunResult(StatusCode.ERROR)
        } else {
          new RunResult(StatusCode.OK)
        }
    }
    val future = new CompletableFuture[RunResult]()
    future.complete(getRunResult)
    future
  }

  private[this] def getTestReport(targetId: BuildTargetIdentifier, results: Seq[TestRunner.Result]): TestReport = {
    val testReport = new TestReport(targetId, 0, 0, 0, 0, 0)
    testReport.setTime(results.map(r => r.duration).sum)
    for (result <- results) {
      result.status match {
        case "Passed" => testReport.setPassed(testReport.getPassed + 1)
        case "Failed" => testReport.setFailed(testReport.getFailed + 1)
        case "Ignored" => testReport.setIgnored(testReport.getIgnored + 1)
        case "Cancelled" => testReport.setCancelled(testReport.getCancelled + 1)
        case "Skipped" => testReport.setSkipped(testReport.getSkipped + 1)
      }
    }
    testReport
  }

  private[this] def getStatusCode(results: Seq[TestRunner.Result]): StatusCode = {
    if ( results.exists(res => res.status == "Failed") ) {
      StatusCode.ERROR
    } else if ( results.exists(res => res.status == "Cancelled") ) {
      StatusCode.CANCELLED
    }else {
      StatusCode.OK
    }
  }

  override def buildTargetTest(testParams: TestParams): CompletableFuture[TestResult] = {
    def getTestResult (implicit ctx: Ctx.Log with Ctx.Home ): TestResult = {

      val argsMap = testParams.getData match {
        case scalaTestParams: ScalaTestParams =>
                              (for (testItem <- scalaTestParams.getTestClasses.asScala)
                               yield (testItem.getTarget, testItem.getClasses.asScala.toSeq)).toMap

        case default => (for (targetId <- testParams.getTargets.asScala) yield (targetId, Seq.empty[String])).toMap
      }
      var overallStatusCode = StatusCode.OK
      for (targetId <- testParams.getTargets.asScala) {
        val module = targetIdToModule(targetId)
        module match {
          case m: TestModule => val testModule = m.asInstanceOf[TestModule]
            val testTask = testModule.test(argsMap(targetId).mkString(" "))

            // send notification to client that testing of this target started
            val taskStartParams = new TaskStartParams(new TaskId(testTask.hashCode().toString))
            taskStartParams.setEventTime(System.currentTimeMillis())
            taskStartParams.setMessage("Testing target: " + targetId)
            taskStartParams.setDataKind("test-task")
            taskStartParams.setData(new TestTask(targetId))
            client.onBuildTaskStart(taskStartParams)

            val runClasspath = getTaskResult(millEvaluator, testModule.runClasspath)
            val frameworks = getTaskResult(millEvaluator, testModule.testFrameworks)
            val compilationResult = getTaskResult(millEvaluator, testModule.compile)

            (runClasspath, frameworks, compilationResult) match {
              case (Success(classpath), Success(testFrameworks), Success(compResult)) =>
                            val (msg, results) = TestRunner.runTests(
                              TestRunner.frameworks(testFrameworks.asInstanceOf[Seq[String]]),
                              classpath.asInstanceOf[Agg[PathRef]].map(_.path),
                              Agg(compResult.asInstanceOf[scalalib.api.CompilationResult].classes.path),
                              argsMap(targetId)
                            )
                            val endTime = System.currentTimeMillis()
                            // send notification to client that testing of this target ended => test report
                            val statusCode = getStatusCode(results)
                            val taskFinishParams = new TaskFinishParams(
                              new TaskId(testTask.hashCode().toString),
                              getStatusCode(results)
                            )
                            taskFinishParams.setEventTime(endTime)
                            taskFinishParams.setMessage("Finished testing target: " +
                              moduleToTarget(targetIdToModule(targetId)).getDisplayName)
                            taskFinishParams.setDataKind("test-report")
                            taskFinishParams.setData(getTestReport(targetId, results))
                            client.onBuildTaskFinish(taskFinishParams)
                            statusCode match {
                              case StatusCode.ERROR => overallStatusCode = StatusCode.ERROR
                              case default =>
                            }
              case default =>   val endTime = System.currentTimeMillis()
                                val taskFinishParams = new TaskFinishParams(
                                    new TaskId(testTask.hashCode().toString),
                                    StatusCode.ERROR
                                  )
                                taskFinishParams.setEventTime(endTime)
                                taskFinishParams.setMessage("Testing target: " +
                                  moduleToTarget(targetIdToModule(targetId)).getDisplayName +
                                  "failed because one of the tasks it depended on failed. There might" +
                                  "be compilation errors.")
                                taskFinishParams.setDataKind("test-report")
                                taskFinishParams.setData(
                                  new TestReport(targetId, 0, 0, 0, 0, 0)
                                )
                                overallStatusCode = StatusCode.ERROR
                                client.onBuildTaskFinish(taskFinishParams)
                                buildTargetCompile(new CompileParams(List(targetId).asJava))
            }


          case default =>
        }
      }
      val testResult = new TestResult(overallStatusCode)
      testParams.getOriginId match {
        case id: String =>
           //TODO: Add the messages from mill to the data field?
          testResult.setOriginId(id)
          testResult
        case default => testResult
      }
    }
    val future = new CompletableFuture[TestResult]()
    future.complete(getTestResult(ctx))
    future
  }

  override def buildTargetCleanCache(cleanCacheParams: CleanCacheParams): CompletableFuture[CleanCacheResult] = ???

  override def buildTargetScalacOptions(scalacOptionsParams: ScalacOptionsParams):
                                                  CompletableFuture[ScalacOptionsResult] = {
    def getScalacOptionsResult: ScalacOptionsResult = {
      var targetScalacOptions = List.empty[ScalacOptionsItem]
      for (targetId <- scalacOptionsParams.getTargets.asScala) {
        val module = targetIdToModule(targetId)
        module match {
          case m: ScalaModule =>
            val options = evaluateInformativeTask(evaluator, m.scalacOptions, Seq.empty[String]).toList
            val classpath = evaluateInformativeTask(evaluator, m.compileClasspath, Agg.empty[PathRef]).
              map(pathRef => pathRef.path.toNIO.toAbsolutePath.toUri.toString).toList
            val index = m.millModuleSegments.parts.length

            val classDirectory = m.millSourcePath.toNIO.toAbsolutePath.toUri.toString

            targetScalacOptions ++= List(new ScalacOptionsItem(targetId, options.asJava, classpath.asJava, classDirectory))
          case m: JavaModule => targetScalacOptions ++= List()
        }
      }
      new ScalacOptionsResult(targetScalacOptions.asJava)
    }

    val future = new CompletableFuture[ScalacOptionsResult]()
    future.complete(getScalacOptionsResult)
    future
  }

  override def buildTargetScalaMainClasses(scalaMainClassesParams: ScalaMainClassesParams):
                                                  CompletableFuture[ScalaMainClassesResult] = {

    def getScalaMainClasses: ScalaMainClassesResult = {
      var items = List.empty[ScalaMainClassesItem]
      for (targetId <- scalaMainClassesParams.getTargets.asScala) {
        val module = targetIdToModule(targetId)
        val scalaMainClasses = getTaskResult(millEvaluator, module.finalMainClassOpt) match {
          case result: Result.Success[Any] => result.asSuccess.get.value match {
            case mainClass: Right[String, String] =>
              List(new ScalaMainClass(
                                mainClass.value,
                                evaluateInformativeTask(evaluator, module.forkArgs, Seq.empty[String]).
                                  toList.asJava,
                                List.empty[String].asJava))
            case msg: Left[String, String] => List.empty[ScalaMainClass]
          }
          case default => List.empty[ScalaMainClass]
        }
        val item = new ScalaMainClassesItem (targetId , scalaMainClasses.asJava)
        items ++= List(item)
        }
      new ScalaMainClassesResult(items.asJava)
    }
    val future = new CompletableFuture[ScalaMainClassesResult]()
    future.complete(getScalaMainClasses)
    future
  }

  private[this] def getTestFrameworks(module: TestModule) (implicit ctx: Ctx.Home): Seq[String] = {
    val runClasspath = getTaskResult(millEvaluator, module.runClasspath)
    val frameworks = getTaskResult(millEvaluator, module.testFrameworks)
    val compilationResult = getTaskResult(millEvaluator, module.compile)

    (runClasspath, frameworks, compilationResult) match {
      case (Result.Success(classpath), Result.Success(testFrameworks), Result.Success(compResult)) =>
        val classFingerprint = Jvm.inprocess(classpath.asInstanceOf[Seq[PathRef]].map(_.path),
          true,
          true,
          false, cl => {
            val fs = TestRunner.frameworks(testFrameworks.asInstanceOf[Seq[String]])(cl)
            fs.flatMap(framework =>
              discoverTests(cl, framework, Agg(compResult.asInstanceOf[CompilationResult].
                classes.path)))
          })
        classFingerprint.map(classF => classF._1.getName.stripSuffix("$"))
      case default => Seq.empty[String] //TODO: or send notification that something went wrong
    }
  }

  override def buildTargetScalaTestClasses(scalaTestClassesParams: ScalaTestClassesParams):
                                                  CompletableFuture[ScalaTestClassesResult] = {
    def getScalaTestClasses (implicit ctx: Ctx.Home): ScalaTestClassesResult = {
      var items = List.empty[ScalaTestClassesItem]
      for (targetId <- scalaTestClassesParams.getTargets.asScala) {
        targetIdToModule(targetId) match {
          case module: TestModule =>
                    items ++= List(new ScalaTestClassesItem(targetId, getTestFrameworks(module).toList.asJava))
          case module: JavaModule => //TODO: maybe send a notification that this target has no test classes
        }
      }
      new ScalaTestClassesResult(items.asJava)
    }
    val future = new CompletableFuture[ScalaTestClassesResult]()
    future.complete(getScalaTestClasses(ctx))
    future
  }

  private[this] def targetToModule(moduleToTargetId: Predef.Map[JavaModule, BuildTargetIdentifier]):
                                                      Predef.Map[BuildTargetIdentifier, JavaModule] = {
      moduleToTargetId.keys.map(mod => (moduleToTargetId(mod), mod)).toMap

  }

  private[this] def getMillModules(ev: Evaluator): Seq[JavaModule] = {
    ev.rootModule.millInternal.segmentsToModules.values.
      collect {
        case m: scalalib.JavaModule => m
      }.toSeq
  }

  private[this] def recomputeTargets(): Unit = {
    millModules = getMillModules(millEvaluator)
    moduleToTargetId = ModuleUtils.getModuleTargetIdMap(millModules)
    targetIdToModule = targetToModule(moduleToTargetId)
    moduleToTarget = ModuleUtils.millModulesToBspTargets(millModules, evaluator, List("scala", "java"))
  }
}
