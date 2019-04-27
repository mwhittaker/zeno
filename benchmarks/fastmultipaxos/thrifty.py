from .fastmultipaxos import *


def _main(args) -> None:
    inputs = [
        Input(
            net_name='SingleSwitchNet',
            f=1,
            num_client_procs=num_client_procs,
            num_clients_per_proc=1,
            round_system_type=RoundSystemType.CLASSIC_ROUND_ROBIN.name,
            duration_seconds=20,
            timeout_seconds=60,
            client_lag_seconds=5,
            profiled=args.profile,
            monitored=args.monitor,
            prometheus_scrape_interval_ms=200,
            acceptor = AcceptorOptions()._replace(
                wait_period_ms = 0,
                wait_stagger_ms = 0,
            ),
            leader = LeaderOptions()._replace(
                thrifty_system = thrifty_system,
            ),
            client = ClientOptions()._replace(
                repropose_period_ms=50,
            ),
        )
        for num_client_procs in range(1, 15)
        for thrifty_system in [
            ThriftySystemType.NOT_THRIFTY,
            ThriftySystemType.RANDOM,
            ThriftySystemType.CLOSEST
        ]
    ] * 3

    def make_net(input) -> FastMultiPaxosNet:
        return SingleSwitchNet(
            f=input.f,
            num_client_procs=input.num_client_procs,
            rs_type = RoundSystemType[input.round_system_type]
        )

    run_suite(args, inputs, make_net)


if __name__ == '__main__':
    _main(get_parser().parse_args())