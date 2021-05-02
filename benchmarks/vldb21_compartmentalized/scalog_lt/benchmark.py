from ...scalog.scalog import *


def main(args) -> None:
    class Suite(ScalogSuite):
        def args(self) -> Dict[Any, Any]:
            return vars(args)

        def inputs(self) -> Collection[Input]:
            return [
                Input(
                    f = 1,
                    num_client_procs = num_client_procs,
                    num_warmup_clients_per_proc = num_clients_per_proc,
                    num_clients_per_proc = num_clients_per_proc,
                    num_shards = num_shards,
                    num_servers_per_shard = 2,
                    num_leaders = 2,
                    num_acceptors = 3,
                    num_replicas = num_replicas,
                    client_jvm_heap_size = '8g',
                    server_jvm_heap_size = '12g',
                    aggregator_jvm_heap_size = '12g',
                    leader_jvm_heap_size = '12g',
                    acceptor_jvm_heap_size = '12g',
                    replica_jvm_heap_size = '12g',
                    measurement_group_size = 100,
                    warmup_duration = datetime.timedelta(seconds=10),
                    warmup_timeout = datetime.timedelta(seconds=15),
                    warmup_sleep = datetime.timedelta(seconds=5),
                    duration = datetime.timedelta(seconds=15),
                    timeout = datetime.timedelta(seconds=20),
                    client_lag = datetime.timedelta(seconds=5),
                    state_machine = 'KeyValueStore',
                    workload_label = 'smoke',
                    workload = workload.UniformSingleKeyWorkload(
                        num_keys=1, size_mean=16, size_std=0),
                    profiled = args.profile,
                    monitored = args.monitor,
                    prometheus_scrape_interval =
                        datetime.timedelta(milliseconds=200),
                    server_options = ServerOptions(
                        push_period = push_period,
                        recover_period = datetime.timedelta(seconds=60),
                    ),
                    server_log_level = args.log_level,
                    aggregator_options = AggregatorOptions(
                        num_shard_cuts_per_proposal = num_shards * 2,
                        recover_period = datetime.timedelta(seconds=60),
                        leader_info_period = datetime.timedelta(seconds=60),
                    ),
                    aggregator_log_level = args.log_level,
                    leader_options = LeaderOptions(
                        resend_phase1as_period = datetime.timedelta(seconds=1),
                        flush_phase2as_every_n = 1,
                        election_options = ElectionOptions(
                            ping_period = datetime.timedelta(seconds=60),
                            no_ping_timeout_min = \
                                datetime.timedelta(seconds=120),
                            no_ping_timeout_max = \
                                datetime.timedelta(seconds=240),
                        ),
                    ),
                    leader_log_level = args.log_level,
                    acceptor_options = AcceptorOptions(),
                    acceptor_log_level = args.log_level,
                    replica_options = ReplicaOptions(
                        log_grow_size = 5000,
                        batch_flush = batch_flush,
                        recover_log_entry_min_period = \
                            datetime.timedelta(seconds=120),
                        recover_log_entry_max_period = \
                            datetime.timedelta(seconds=240),
                    ),
                    replica_log_level = args.log_level,
                    client_options = ClientOptions(
                        resend_client_request_period = \
                            datetime.timedelta(seconds=1),
                    ),
                    client_log_level = args.log_level,
                )

                # Hyperparamter tuning.
                #
                # - Without batch flushing and with 2 replicas, everything
                #   seems to bottleneck at just below 200,000 requests per
                #   second. Looking at grafana, every non-server component has
                #   very low inbound load. I think the replicas replying to the
                #   clients are the bottleneck here.
                # - Adding more replicas, adding batch flushing, and adding
                #   more shards didn't increase throughput.
                for ppm in [1, 2, 3, 4, 5]
                for c in [1, 5, 10, 15, 20]
                for (
                    num_shards,           # 0
                    push_period_ms,       # 1
                    num_replicas,         # 2
                    batch_flush,          # 3
                    num_client_procs,     # 4
                    num_clients_per_proc, # 5
                ) in [
                    # 0  1  2      3   4    5
                    ( 1, ppm, 2, True, c, 100),
                ]

                for push_period in [
                    datetime.timedelta(milliseconds=push_period_ms)
                ]
            ] * 3

        def summary(self, input: Input, output: Output) -> str:
            push_period_s = input.server_options.push_period.total_seconds()
            return str({
                'num_client_procs':
                    input.num_client_procs,
                'num_clients_per_proc':
                    input.num_clients_per_proc,
                'num_shards':
                    input.num_shards,
                'push_period':
                    f'{push_period_s * 1000}ms',
                'num_replicas':
                    input.num_replicas,
                'batch_flush':
                    input.replica_options.batch_flush,
                'latency.median_ms': \
                    f'{output.output.latency.median_ms:.6}',
                'start_throughput_1s.p90': \
                    f'{output.output.start_throughput_1s.p90:.8}',
            })

    suite = Suite()
    with benchmark.SuiteDirectory(args.suite_directory, 'scalog_lt') as dir:
        suite.run_suite(dir)


if __name__ == '__main__':
    main(get_parser().parse_args())
