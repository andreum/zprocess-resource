import br.com.inbot.os._
import zio._
import zio.stream.ZStream
import zio.test._

object ZProcessResourceTest extends ZIOSpecDefault {

    def spec = suite("ZProcessResourceTest") {
        test("ls has etc") {
            for {
                pr <- ZProcessResource.apply(List("/bin/ls", "/"), Map.empty, None)
                out <- pr.stdout.runCollect
            } yield {
                assertTrue(pr.proc != null)
                assertTrue(out.filter(str => str.contains("etc")).nonEmpty)
            }
        }
        test("echo returns itself") {
            for {
                pr <- ZProcessResource.apply(List("/bin/echo", "123"), Map.empty, None)
                out <- pr.stdout.runCollect
            } yield {
                assertTrue(pr.proc != null)
                assertTrue(out.filter(str => str.contains("123")).nonEmpty)
            }
        }
        test("can write and read with cat") {
            for {
                txt <- ZStream.iterate(0) (_ + 1).take(1000)
                    .map(i => s"line: ${i}... another one\n")
                    .runCollect.map(_.mkString)
                pr <- ZProcessResource(List("/bin/cat"), Map.empty, None)
                srcStream = ZStream(txt).map(_.toString)
                outF <- pr.stdout.runCollect.fork
                inF <- srcStream.run(pr.stdin).fork
                out <- outF.join.map(_.mkString)
                _ <- inF.join
            } yield {
                assert(out)(Assertion.equalTo(txt))
            }
        }

    }
}