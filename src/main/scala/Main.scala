import br.com.inbot.os.ZProcessResource
import zio.Console.printLine
import zio._
import zio.stream.{ZPipeline, ZStream}

object Main extends ZIOAppDefault {
  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    val prog = for {
      pr <- ZProcessResource(List("/bin/cat"), Map.empty, None)
      _ <- printLine(s"proc: ${pr}")
      outF <- (printLine("collecting output from proc\n") *>
          pr.stdout
            .via(ZPipeline.splitLines)
            .tap {l => printLine(s"from proc: ${l}")}
            .runCollect <* printLine("end of output from proc")
          )
          .fork
     // _ <- printLine(s"outF: ${outF}")
      source = ZStream
          .iterate(0)(_ + 1)
          .take(100)
          .map(_.toString + "\n")
          //.tap(l => Console.print(s"to proc: ${l}"))
      inF <- source.run(pr.stdin).fork
      _ <- printLine(s"inF: ${inF}")
      out <- outF.join.map(_.head)
      _ <- inF.join
      result: Process <- pr.waitFor
      _ <- printLine(s"result: ${result.exitValue()}")
    } yield {
      ()
    }
    prog.either.forEachZIO(l => printLine(s"SAIDA: ${l}"))
  }

}