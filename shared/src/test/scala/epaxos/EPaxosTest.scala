package frankenpaxos.epaxos

import frankenpaxos.simulator.BadHistory
import frankenpaxos.simulator.Simulator
import org.scalatest.FlatSpec

class EPaxosTest extends FlatSpec {
  "An EPaxos instance" should "work correctly" in {
    for (f <- 1 to 3) {
      val sim = new SimulatedEPaxos(f)
      Simulator
        .simulate(sim, runLength = 50, numRuns = 2000)
        .flatMap(b => Simulator.minimize(sim, b.history)) match {
        case Some(BadHistory(history, throwable)) => {
          // https://stackoverflow.com/a/1149712/3187068
          val sw = new java.io.StringWriter()
          val pw = new java.io.PrintWriter(sw)
          throwable.printStackTrace(pw)

          val formatted_history = history.map(_.toString).mkString("\n")
          fail(s"$sw\n${sim.historyToString(history)}")
        }
        case None => {}
      }
    }
  }
}
