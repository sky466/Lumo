package com.letvcast.player;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描：找同网段里开着 445（SMB）/ 139（NetBIOS）的机器。
 *
 * <p>老电视上打字太痛苦，很多人不是不会填，是根本不知道 IP 和共享名。
 * 这里只做「谁在 445 上应答」这一件事，1 秒内扫完 /24，点到哪个就把地址填进去。
 */
public class LanScan {

    public interface Callback {
        void onDone(List<String> hosts);
    }

    private static final int THREADS = 64;
    private static final int CONNECT_TIMEOUT_MS = 350;

    public static void scan(final Callback cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> found = Collections.synchronizedList(new ArrayList<String>());
                try {
                    final List<String> targets = candidates();
                    if (targets.isEmpty()) {
                        LogBus.mark("SCAN", "no local ipv4 interface");
                    } else {
                        LogBus.mark("SCAN", "scanning " + targets.size() + " hosts");
                    }
                    final AtomicInteger idx = new AtomicInteger(0);
                    int workers = Math.min(THREADS, Math.max(1, targets.size()));
                    final CountDownLatch done = new CountDownLatch(workers);
                    for (int i = 0; i < workers; i++) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    while (true) {
                                        int i = idx.getAndIncrement();
                                        if (i >= targets.size()) break;
                                        String ip = targets.get(i);
                                        if (probe(ip, 445) || probe(ip, 139)) {
                                            found.add(ip);
                                        }
                                    }
                                } finally {
                                    done.countDown();
                                }
                            }
                        }, "lan-scan").start();
                    }
                    done.await();
                } catch (Throwable t) {
                    LogBus.mark("SCAN", "scan failed: " + t);
                }
                // 按 IP 末段排序，看起来更顺
                List<String> out = new ArrayList<>(found);
                Collections.sort(out, new java.util.Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        return lastOctet(a) - lastOctet(b);
                    }
                });
                LogBus.mark("SCAN", "found " + out.size() + " host(s)");
                cb.onDone(out);
            }
        }, "lan-scan-main");
        t.setDaemon(true);
        t.start();
    }

    private static int lastOctet(String ip) {
        int i = ip.lastIndexOf('.');
        try {
            return i > 0 ? Integer.parseInt(ip.substring(i + 1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean probe(String ip, int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new java.net.InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 本机所有 IPv4 所在 /24 段里的地址（去掉自己的和 .0/.255） */
    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    int cut = ip.lastIndexOf('.');
                    if (cut <= 0) continue;
                    String prefix = ip.substring(0, cut);
                    String self = ip.substring(cut + 1);
                    if (prefix.startsWith("127.")) continue;
                    for (int i = 1; i <= 254; i++) {
                        if (String.valueOf(i).equals(self)) continue;
                        String x = prefix + "." + i;
                        if (!list.contains(x)) list.add(x);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return list;
    }
}
