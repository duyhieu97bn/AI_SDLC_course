from site_connector.traffic import parse_net_dev, route_interface

NET_DEV = """Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:    1000      10    0    0    0     0          0         0     1000      10    0    0    0     0       0          0
  eth0: 5000000    4000    0    0    0     0          0         0 90000000   70000    0    0    0     0       0          0
  eth1:     700       7    0    0    0     0          0         0      300       3    0    0    0     0       0          0
"""

# eth0: 172.30.0.0/16 (mạng ra máy chủ), eth1: 172.31.0.0/16 (LAN camera) và tuyến mặc định qua eth1
NET_ROUTE = """Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT
eth1\t00000000\t01001FAC\t0003\t0\t0\t0\t00000000\t0\t0\t0
eth0\t00001EAC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0
eth1\t00001FAC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0
"""


def test_parse_net_dev() -> None:
    counters = parse_net_dev(NET_DEV)
    assert counters["eth0"] == (5000000, 90000000)
    assert counters["lo"] == (1000, 1000)


def test_route_interface_longest_prefix() -> None:
    assert route_interface(NET_ROUTE, "172.30.0.5") == "eth0"
    assert route_interface(NET_ROUTE, "172.31.4.4") == "eth1"
    assert route_interface(NET_ROUTE, "8.8.8.8") == "eth1"  # tuyến mặc định
