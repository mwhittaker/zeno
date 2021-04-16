package frankenpaxos.scalog

import collection.mutable
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.monitoring.Summary
import frankenpaxos.roundsystem.RoundSystem
import frankenpaxos.util
import scala.scalajs.js.annotation._

@JSExportAll
object AggregatorInboundSerializer extends ProtoSerializer[AggregatorInbound] {
  type A = AggregatorInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

@JSExportAll
object Aggregator {
  val serializer = AggregatorInboundSerializer
}

@JSExportAll
case class AggregatorOptions(
    // The aggregator periodically receives shard cuts from servers. The
    // aggregator periodically aggregates these shard cuts into a global cut
    // and proposes the global cut to the Paxos leader. The aggregator will
    // propose a global cut after every `numShardCutsPerProposal` shard cuts
    // that it receives.
    numShardCutsPerProposal: Int,
    // If the aggregator has a hole in its log of cuts for more than
    // `recoverPeriod`, it polls the Paxos leader to fill it.
    recoverPeriod: java.time.Duration,
    // The aggregator implements its log of raw cuts as a BufferMap. This is
    // the BufferMap's `logGrowSize`.
    logGrowSize: Int,
    // If `unsafeDontRecover` is true, the aggregator doesn't make any attempt
    // to recover cuts. This is not live and should only be used for
    // performance debugging.
    unsafeDontRecover: Boolean,
    // Whether or not we should measure the latency of processing every request.
    measureLatencies: Boolean
)

@JSExportAll
object AggregatorOptions {
  val default = AggregatorOptions(
    numShardCutsPerProposal = 2,
    recoverPeriod = java.time.Duration.ofSeconds(1),
    logGrowSize = 5000,
    unsafeDontRecover = false,
    measureLatencies = true
  )
}

@JSExportAll
class AggregatorMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("scalog_aggregator_requests_total")
    .labelNames("type")
    .help("Total number of processed requests.")
    .register()

  val requestsLatency: Summary = collectors.summary
    .build()
    .name("scalog_aggregator_requests_latency")
    .labelNames("type")
    .help("Latency (in milliseconds) of a request.")
    .register()

  val proposalsSent: Counter = collectors.counter
    .build()
    .name("scalog_aggregator_proposals_sent")
    .help("Total number of proposals sent.")
    .register()

  val numPrunedCuts: Counter = collectors.counter
    .build()
    .name("scalog_aggregator_num_pruned_cuts")
    .help(
      "Total number of raw cuts that the aggregator prunes because it does " +
        "not obey the monotonic order of cuts."
    )
    .register()

  val recoversSent: Counter = collectors.counter
    .build()
    .name("scalog_aggregator_recovers_sent")
    .help("Total number of Recovers sent.")
    .register()
}

@JSExportAll
class Aggregator[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    options: AggregatorOptions = AggregatorOptions.default,
    metrics: AggregatorMetrics = new AggregatorMetrics(PrometheusCollectors)
) extends Actor(address, transport, logger) {
  config.checkValid()
  logger.check(config.aggregatorAddress == address)

  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = AggregatorInbound
  override val serializer = AggregatorInboundSerializer

  type Slot = Int
  type Nonce = Int
  type Cut = Seq[Slot]

  // Fields ////////////////////////////////////////////////////////////////////
  // Server channels.
  private val servers: Seq[Chan[Server[Transport]]] =
    for (shard <- config.serverAddresses; a <- shard)
      yield chan[Server[Transport]](a, Server.serializer)

  // Leader channels.
  private val leaders: Seq[Chan[Leader[Transport]]] =
    for (a <- config.leaderAddresses)
      yield chan[Leader[Transport]](a, Leader.serializer)

  // The round system used by the leaders.
  private val roundSystem =
    new RoundSystem.ClassicRoundRobin(config.leaderAddresses.size)

  // The largest round we know of. roundSystem.leader(round) is who we think is
  // the current active leader.
  @JSExportAll
  protected var round = 0

  // a log for the raw cuts
  // a log for the pruned cuts
  //
  // a collection of pending proposals
  // a timer to send out leader infos

  // Imagine we have a Scalog deployment with three shards with servers [a, b],
  // [c, d], and [e, f, g]. Then, shardCuts looks something like the following.
  //
  //      +----------+
  //      | +------+ |
  //      | |[1, 1]| |
  //    0 | +------+ |
  //      | |[0, 2]| |
  //      | +------+ |
  //      +----------+
  //      | +------+ |
  //      | |[2, 4]| |
  //    1 | +------+ |
  //      | |[2, 2]| |
  //      | +------+ |
  //      +----------+
  //      | +------+ |
  //      | |[4, 3]| |
  //      | +------+ |
  //    2 | |[2, 1]| |
  //      | +------+ |
  //      | |[0, 1]| |
  //      | +------+ |
  //      +----------+
  //
  // We would collapse this into the global cut [1, 2, 2, 4, 4, 3].
  @JSExportAll
  protected val shardCuts: mutable.Buffer[mutable.Buffer[Cut]] =
    config.serverAddresses
      .map(shard => {
        shard.map(foo => Seq.fill(shard.size)(0)).to[mutable.Buffer]
      })
      .to[mutable.Buffer]

  @JSExportAll
  protected var numShardCutsSinceLastProposal: Int = 0

  // The aggregator periodically proposes global cuts to the Paxos leader. It
  // associates every proposal with a nonce to make tracking the proposals
  // easier. This is a nonce that is attached to every proposal and
  // subsequently incremented.
  @JSExportAll
  protected var proposalNonce: Int = 0

  // The log of raw cuts decided by Paxos. cuts is a pruned version of rawCuts
  // that is guaranteed to have monotonically increasing cuts. For example:
  //
  //               0   1   2   3   4
  //             +---+---+---+---+---+---
  //     rawCuts |0,0|1,2|2,1|2,2|1,1| ...
  //             +---+---+---+---+---+---
  //             +---+---+---+---+---+---
  //        cuts |0,0|1,2|2,2|   |   | ...
  //             +---+---+---+---+---+---
  //
  // Note that raw cut 2 was pruned because it is not monotonically larger than
  // raw cut 1. Similarly, raw cut 4 was pruned.
  //
  // How is it possible for rawCuts not be monotonically increasing? The raw
  // cuts that the aggregator proposes are monotonically increasing, but they
  // may arrive out of order at the Paxos leader and may be ordered in
  // non-monontically increasing order.
  @JSExportAll
  protected val rawCuts: util.BufferMap[GlobalCutOrNoop] =
    new util.BufferMap(options.logGrowSize)

  @JSExportAll
  protected val cuts: mutable.Buffer[Cut] = mutable.Buffer()

  // Every log entry < rawCutsWatermark is chosen in rawCuts. Entry
  // rawCutsWatermark is not chosen.
  @JSExportAll
  protected var rawCutsWatermark: Int = 0

  // The set of all proposals for which we have not received a reply.
  @JSExportAll
  protected val pendingProposals: mutable.Map[Nonce, Proposal] = mutable.Map()

  // The aggregator could have one timer for every pending proposal, but this
  // would generate a lot of timers, and I'm nervous this would be slow,
  // especially since the timers would fire very rarely. Instead, we have a
  // single timer that tracks a single proposal. If the proposal doesn't
  // receive a response in time, the timer is fired and the proposal is resent.
  // If a response is received in time, the timer is reset for another
  // proposal. If it is rare for the timer to fire, then this approach requires
  // only one timer and has good performance. If it is common for the timer to
  // fire, then this approach is very slow, as only one proposal is resent at a
  // time.
  @JSExportAll
  protected val timedNonce: Option[Int] = None

  // TODO(mwhittaker): Rename resend timer. Add a recover timer as well.
  @JSExportAll
  protected val recoverTimer: Transport#Timer = {
    lazy val t: Transport#Timer = timer(
      s"recoverTimer",
      options.recoverPeriod,
      () => {
        // TODO(mwhittaker): Implement.
        metrics.recoversSent.inc()
        t.start()
      }
    )
    t.start()
    t
  }

  // Helpers ///////////////////////////////////////////////////////////////////
  private def timed[T](label: String)(e: => T): T = {
    if (options.measureLatencies) {
      val startNanos = System.nanoTime
      val x = e
      val stopNanos = System.nanoTime
      metrics.requestsLatency
        .labels(label)
        .observe((stopNanos - startNanos).toDouble / 1000000)
      x
    } else {
      e
    }
  }

  private def pairwiseMax(xs: Seq[Int], ys: Seq[Int]): Seq[Int] = {
    require(xs.size == ys.size)
    xs.zip(ys).map({ case (x, y) => Math.max(x, y) })
  }

  private def pairwiseMax(seqs: Seq[Seq[Int]]): Seq[Int] = {
    require(seqs.size > 0)
    seqs.reduce(pairwiseMax)
  }

  private def monotonicallyLt(xs: Seq[Int], ys: Seq[Int]): Boolean = {
    require(xs.size == ys.size)
    xs != ys && xs.zip(ys).forall({ case (x, y) => x <= y })
  }

  // Handlers //////////////////////////////////////////////////////////////////
  override def receive(src: Transport#Address, inbound: InboundMessage) = {
    import AggregatorInbound.Request

    val label =
      inbound.request match {
        case Request.ShardInfo(_)       => "ShardInfo"
        case Request.RawCutChosen(_)    => "RawCutChosen"
        case Request.LeaderInfoReply(_) => "LeaderInfoReply"
        case Request.Recover(_)         => "Recover"
        case Request.Empty =>
          logger.fatal("Empty AggregatorInbound encountered.")
      }
    metrics.requestsTotal.labels(label).inc()

    timed(label) {
      inbound.request match {
        case Request.ShardInfo(r)       => handleShardInfo(src, r)
        case Request.RawCutChosen(r)    => handleRawCutChosen(src, r)
        case Request.LeaderInfoReply(r) => handleLeaderInfoReply(src, r)
        case Request.Recover(r)         => handleRecover(src, r)
        case Request.Empty =>
          logger.fatal("Empty AggregatorInbound encountered.")
      }
    }
  }

  private def handleShardInfo(
      src: Transport#Address,
      shardInfo: ShardInfo
  ): Unit = {
    // Update the server's shard cut.
    shardCuts(shardInfo.shardIndex)(shardInfo.serverIndex) = pairwiseMax(
      shardCuts(shardInfo.shardIndex)(shardInfo.serverIndex),
      shardInfo.watermark
    )

    // We send a proposal every numShardCutsPerProposal cuts.
    numShardCutsSinceLastProposal += 1
    if (numShardCutsSinceLastProposal >= options.numShardCutsPerProposal) {
      leaders(roundSystem.leader(round)).send(
        LeaderInbound().withProposeCut(
          ProposeCut(GlobalCut(shardCuts.map(pairwiseMax).flatten))
        )
      )
      numShardCutsSinceLastProposal = 0
    }
  }

  private def handleRawCutChosen(
      src: Transport#Address,
      rawCutChosen: RawCutChosen
  ): Unit = {
    rawCuts.put(rawCutChosen.slot, rawCutChosen.rawCutOrNoop)

    while (rawCuts.get(rawCutsWatermark).isDefined) {
      rawCuts.get(rawCutsWatermark).map(_.value) match {
        case None =>
          logger.fatal("Unreachable code.")

        case Some(GlobalCutOrNoop.Value.Noop(Noop())) =>
          // Do nothing.
          ()

        case Some(GlobalCutOrNoop.Value.GlobalCut(globalCut)) =>
          val cut = globalCut.watermark
          if (cuts.isEmpty || monotonicallyLt(cuts(rawCutsWatermark - 1), cut)) {
            val slot = cuts.size
            cuts += cut
            for (server <- servers) {
              server.send(
                ServerInbound().withCutChosen(
                  CutChosen(slot = slot, cut = GlobalCut(watermark = cut))
                )
              )
            }
          }

        case Some(GlobalCutOrNoop.Value.Empty) =>
          logger.fatal("Empty GlobalCutOrNoop encountered.")
      }

      rawCutsWatermark += 1
    }

    // TODO(mwhittaker): Update the recovery timer.
  }

  private def handleLeaderInfoReply(
      src: Transport#Address,
      leaderInfoReply: LeaderInfoReply
  ): Unit = {
    round = Math.max(round, leaderInfoReply.round)
  }

  // TODO(mwhittaker): Extract logic and add unit test.
  private def handleRecover(src: Transport#Address, recover: Recover): Unit = {
    // If a replica has a hole in its log for too long, it sends us a recover
    // message. We have to figure out which server owns this slot and what is
    // the corresponding cut. See `projectCut` inside of Server for a picture
    // to reference when thinking about this translation.
    //
    // TODO(mwhittaker): For now, we implement this as a linear search. This
    // performs increasingly poorly over time as the number of cuts increases.
    // We can implement binary search to implement this much faster.

    var start = 0
    var stop = 0
    for ((cut, i) <- cuts.zipWithIndex) {
      stop = cut.sum
      if (start <= recover.slot && recover.slot < stop) {
        val previousCut = if (recover.slot == 0) {
          cut.map(_ => 0)
        } else {
          cuts(recover.slot - 1)
        }
        val diffs = cut.zip(previousCut).map({ case (x, y) => x - y })
        stop = start
        for ((diff, j) <- diffs.zipWithIndex) {
          stop += diff
          if (start <= recover.slot && recover.slot < stop) {
            servers(j).send(
              ServerInbound()
                .withCutChosen(CutChosen(slot = i, cut = GlobalCut(cut)))
            )
            return
          }
          start = stop
        }
      }
      start = stop
    }

    // We didn't find a corresponding cut. We ignore the recover.
  }
}
