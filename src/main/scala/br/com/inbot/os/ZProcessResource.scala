package br.com.inbot.os

import br.com.inbot.os.ZProcessResource.exceptions.{ErrorWithStderr, ErrorWithStdin, ErrorWithStdout, ProcessError}
import zio.stream.{ZPipeline, ZSink, ZStream}
import zio.{Scope, URIO, ZIO}

import java.io.File

object ZProcessResource {
    object exceptions {
        abstract class ProcessError(msg: String) extends Exception(msg) with Product
        case class ExceptionInProcess(msg: String, err: Throwable) extends ProcessError(msg)
        case class ErrorCreatingProcess(msg: String, err: Throwable) extends ProcessError(msg)
        abstract class ProcessIOError(msg: String) extends ProcessError(msg)
        case class ErrorWithStdin(err: Throwable) extends ProcessIOError(err.getMessage)
        case class ErrorWithStdout(err: Throwable) extends ProcessIOError(err.getMessage)
        case class ErrorWithStderr(err: Throwable) extends ProcessIOError(err.getMessage)
    }

    /**
     * Represents a created process with all the extra information
     *
     * @tparam F the effect to use
     * @tparam T stream base type (usually Byte or Stream)
     * @param proc   java Process object
     * @param stdin  An FS2 Pipe one can write to and have the data be sent to the process
     * @param stdout Stream with the process standard output
     * @param stderr Stream with the process error output
     */
    case class ScopedProcess[T](proc: Process,
                                stdin: ZSink[Any, exceptions.ProcessIOError, T, Byte, Long],
                                stdout: ZStream[Any, exceptions.ProcessIOError, T],
                                stderr: ZStream[Any, exceptions.ProcessIOError, T]) {
        /**
         * Waits for the process end
         *
         * @return
         */
        def waitFor: ZIO[Any, exceptions.ExceptionInProcess, Process] = {
            ZIO.fromCompletableFuture(proc.onExit()).mapError { err =>
                exceptions.ExceptionInProcess("Error in waitFor", err)
            }
        }

        def cancel: ZIO[Any, Nothing, Unit] = {
            ZIO.succeed(proc.destroy())
        }

        def isTerminated: ZIO[Any, Nothing, Boolean] = {
            ZIO.attemptBlocking(proc.exitValue())
                .either
                .map(_.isRight)
        }
    }

    /**
     * Run a Process, working with Bytes. The returned ScopedProcess[Byte] has an stdin, stdout and stderr, which can be
     *  read from and written to, and you can wait on the result. Careful not to block when reading from stdout and stderr, though
     * @param command command to run
     * @param environment environment
     * @param workingDirectory
     * @return
     */
    def runProcessRaw(command: Seq[String], environment: Map[String, String] = Map.empty, workingDirectory: Option[File] = None): ZIO[Scope, ProcessError, ScopedProcess[Byte]] = {
        import exceptions._
        val acquireProc: ZIO[Any, ProcessError, Process] =
            ZIO
                .attempt {
                        val pb = new ProcessBuilder(command: _*)
                        if (workingDirectory.isDefined)
                            pb.directory(workingDirectory.get)
                        val envMap = pb.environment()
                        for {
                            (k, v) <- environment
                        } {
                            envMap.put(k, v)
                        }
                        val proc: Process = pb.start()
                        proc
                    }
                .mapError(thr => ErrorCreatingProcess("Error in acquireProc", thr))

        val releaseProc: Process => URIO[Any, Either[Throwable, Unit]] = { (proc: Process) =>
            ZIO.attemptBlocking(proc.destroy()).either
        }

        val procResource: ZIO[Scope, ProcessError, Process] =
            ZIO.acquireRelease(acquireProc)(releaseProc)

        val fullProcR: ZIO[Scope, ProcessError, ScopedProcess[Byte]] = for {
            proc <- procResource
            stdinZ = ZIO.fromAutoCloseable(ZIO.succeed(proc.getOutputStream))
            stdoutZ = ZIO.fromAutoCloseable(ZIO.succeed(proc.getInputStream))
            stderrZ = ZIO.fromAutoCloseable(ZIO.succeed(proc.getErrorStream))
        } yield {
            val stdinSink =
                ZSink
                    .fromOutputStreamScoped(stdinZ)
                    .mapError(ErrorWithStdin(_): ProcessIOError)
            val stdoutSource = ZStream
                .fromInputStreamScoped(stdoutZ)
                .mapError(ErrorWithStdout)
            val stderrSource = ZStream
                .fromInputStreamScoped(stderrZ)
                .mapError(ErrorWithStderr)

            ScopedProcess[Byte](proc, stdinSink, stdoutSource, stderrSource)
        }
        fullProcR
    }

    /**
     * Run a Process, working with Strings. The returned ScopedProcess[String] has an stdin, stdout and stderr, which are ZSink and ZStreams and can be
     * read from and written to. You can also wait on the result. Careful not to block when reading from stdout and stderr, though
     *
     * @param command     command to run
     * @param environment environment
     * @param workingDirectory
     * @return
     */
    def runProcess(command: Seq[String], environment: Map[String, String], workingDirectory: Option[File]): ZIO[Scope, ProcessError, ScopedProcess[String]] = {
        val proc: ZIO[Scope, ProcessError, ScopedProcess[Byte]] = runProcessRaw(command, environment, workingDirectory)
        convertRawProcessToString(proc)
    }

    /**
     *
     * Executes a command using the specified environment and working directory, optionally providing input via stdin.
     * This method returns the output (stdout) and, if present, the errors (stderr) as a tuple.
     *
     * @param command          A sequence of strings representing the command to be executed and its arguments.
     * @param environment      A map of environment variables to be used when running the command (default is an empty map).
     * @param workingDirectory An optional working directory for the command (default is None, which uses the current working directory).
     * @param stdin            An optional string to provide as input to the process via stdin (default is None, which means no input is provided).
     * @return A ZIO effect that, when executed, returns a tuple containing the stdout as a string and an optional stderr string (if present).
     */
    def executeCommand(command: Seq[String],
                       environment: Map[String, String] = Map.empty,
                       workingDirectory: Option[File] = None,
                       stdin: Option[String] = None): ZIO[Scope, ProcessError, (String, String)] = {
        for {
            scopedProcess <- runProcess(command, environment, workingDirectory)
            stdinFiber <- stdin.fold[URIO[Scope, Unit]](ZIO.unit[Any, Nothing])(input => ZStream.succeed(input.getBytes) >>> scopedProcess.stdin).fork
            stdoutFiber <- scopedProcess.stdout.runCollect.mapBoth(err => ErrorWithStdout(err), _.mkString).fork
            stderrFiber <- scopedProcess.stderr.runCollect.mapBoth(err => ErrorWithStderr(err), _.mkString).fork
            (stdout, stderr) <- (stdinFiber.join).zip(stdoutFiber.join.zip(stderrFiber.join))
        } yield (stdout, stderr)
    }

    /**e
     * Transforms a ProcessResource object that does I/O with Bytes into one that works with utf8 Strings
     *
     */
    def convertRawProcessToString[R, E](resource: ZIO[R, E, ScopedProcess[Byte]]): ZIO[R, E, ScopedProcess[String]] = {
        resource.map { proc: ScopedProcess[Byte] =>
            val stdin: ZSink[Any, exceptions.ProcessIOError, Byte, Byte, Long] = proc.stdin
            val encoder: ZPipeline[Any, ErrorWithStdin, String, Byte] = ZPipeline.utf8Encode.mapError(ErrorWithStdin)
            val newStdin: ZSink[Any, exceptions.ProcessIOError, String, Byte, Long] = encoder >>> stdin
            ScopedProcess[String](
                proc.proc,
                newStdin,
                proc.stdout.via(ZPipeline.utf8Decode.mapError(ErrorWithStdout)),
                proc.stderr.via(ZPipeline.utf8Decode.mapError(ErrorWithStderr))
            )
        }
    }

}
