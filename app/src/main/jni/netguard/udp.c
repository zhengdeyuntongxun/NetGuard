/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2016 by Marcel Bokhorst (M66B)
*/

#include "netguard.h"

struct udp_session *udp_session = NULL;
extern FILE *pcap_file;

void init_udp(const struct arguments *args) {
    udp_session = NULL;
}

void clear_udp() {
    struct udp_session *u = udp_session;
    while (u != NULL) {
        if (u->socket >= 0 && close(u->socket))
            log_android(ANDROID_LOG_ERROR, "UDP close %d error %d: %s",
                        -u->socket, errno, strerror(errno));
        struct udp_session *p = u;
        u = u->next;
        free(p);
    }
    udp_session = NULL;
}

int get_udp_sessions() {
    int count = 0;
    struct udp_session *uc = udp_session;
    while (uc != NULL) {
        if (uc->state == UDP_ACTIVE)
            count++;
        uc = uc->next;
    }
    return count;
}

int get_udp_timeout(const struct udp_session *u, int sessions, int maxsessions) {
    int timeout = (ntohs(u->dest) == 53 ? UDP_TIMEOUT_53 : UDP_TIMEOUT_ANY);

    int scale = 100 - sessions * 100 / maxsessions;
    timeout = timeout * scale / 100;

    return timeout;
}

void check_udp_sessions(const struct arguments *args, int sessions, int maxsessions) {
    time_t now = time(NULL);

    struct udp_session *ul = NULL;
    struct udp_session *u = udp_session;
    while (u != NULL) {
        char source[INET6_ADDRSTRLEN + 1];
        char dest[INET6_ADDRSTRLEN + 1];
        if (u->version == 4) {
            inet_ntop(AF_INET, &u->saddr.ip4, source, sizeof(source));
            inet_ntop(AF_INET, &u->daddr.ip4, dest, sizeof(dest));
        }
        else {
            inet_ntop(AF_INET6, &u->saddr.ip6, source, sizeof(source));
            inet_ntop(AF_INET6, &u->daddr.ip6, dest, sizeof(dest));
        }

        // Check session timeout
        int timeout = get_udp_timeout(u, sessions, maxsessions);
        if (u->state == UDP_ACTIVE && u->time + timeout < now) {
            log_android(ANDROID_LOG_WARN, "UDP idle %d/%d sec state %d from %s/%u to %s/%u",
                        now - u->time, timeout, u->state,
                        source, ntohs(u->source), dest, ntohs(u->dest));
            u->state = UDP_FINISHING;
        }

        // Check finished sessions
        if (u->state == UDP_FINISHING) {
            log_android(ANDROID_LOG_INFO, "UDP close from %s/%u to %s/%u socket %d",
                        source, ntohs(u->source), dest, ntohs(u->dest), u->socket);

            if (close(u->socket))
                log_android(ANDROID_LOG_ERROR, "UDP close %d error %d: %s",
                            u->socket, errno, strerror(errno));
            u->socket = -1;

            u->time = time(NULL);
            u->state = UDP_CLOSED;
        }

        if (u->state == UDP_CLOSED && (u->sent || u->received)) {
            account_usage(args, u->version, IPPROTO_UDP,
                          dest, ntohs(u->dest), u->uid, u->sent, u->received);
            u->sent = 0;
            u->received = 0;
        }

        // Cleanup lingering sessions
        if ((u->state == UDP_CLOSED || u->state == UDP_BLOCKED) &&
            u->time + UDP_KEEP_TIMEOUT < now) {
            if (ul == NULL)
                udp_session = u->next;
            else
                ul->next = u->next;

            struct udp_session *c = u;
            u = u->next;
            free(c);
        }
        else {
            ul = u;
            u = u->next;
        }
    }
}

void check_udp_sockets(const struct arguments *args, fd_set *rfds, fd_set *wfds, fd_set *efds) {
    struct udp_session *cur = udp_session;
    while (cur != NULL) {
        if (cur->socket >= 0) {
            // Check socket error
            if (FD_ISSET(cur->socket, efds)) {
                cur->time = time(NULL);

                int serr = 0;
                socklen_t optlen = sizeof(int);
                int err = getsockopt(cur->socket, SOL_SOCKET, SO_ERROR, &serr, &optlen);
                if (err < 0)
                    log_android(ANDROID_LOG_ERROR, "UDP getsockopt error %d: %s",
                                errno, strerror(errno));
                else if (serr)
                    log_android(ANDROID_LOG_ERROR, "UDP SO_ERROR %d: %s", serr, strerror(serr));

                cur->state = UDP_FINISHING;
            }
            else {
                // Check socket read
                if (FD_ISSET(cur->socket, rfds)) {
                    cur->time = time(NULL);

                    uint8_t *buffer = malloc(cur->mss);
                    ssize_t bytes = recv(cur->socket, buffer, cur->mss, 0);
                    if (bytes < 0) {
                        // Socket error
                        log_android(ANDROID_LOG_WARN, "UDP recv error %d: %s",
                                    errno, strerror(errno));

                        if (errno != EINTR && errno != EAGAIN)
                            cur->state = UDP_FINISHING;
                    }
                    else if (bytes == 0) {
                        log_android(ANDROID_LOG_WARN, "UDP recv eof");
                        cur->state = UDP_FINISHING;

                    } else {
                        // Socket read data
                        char dest[INET6_ADDRSTRLEN + 1];
                        if (cur->version == 4)
                            inet_ntop(AF_INET, &cur->daddr.ip4, dest, sizeof(dest));
                        else
                            inet_ntop(AF_INET6, &cur->daddr.ip6, dest, sizeof(dest));
                        log_android(ANDROID_LOG_INFO, "UDP recv bytes %d from %s/%u for tun",
                                    bytes, dest, ntohs(cur->dest));

                        cur->received += bytes;

                        // Process DNS response
                        if (ntohs(cur->dest) == 53)
                            parse_dns_response(args, buffer, (size_t) bytes);

                        // Forward to tun
                        if (write_udp(args, cur, buffer, (size_t) bytes) < 0)
                            cur->state = UDP_FINISHING;
                        else {
                            // Prevent too many open files
                            if (ntohs(cur->dest) == 53)
                                cur->state = UDP_FINISHING;
                        }
                    }
                    free(buffer);
                }
            }
        }
        cur = cur->next;
    }
}

int has_udp_session(const struct arguments *args, const uint8_t *pkt, const uint8_t *payload) {
    // Get headers
    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct udphdr *udphdr = (struct udphdr *) payload;

    if (ntohs(udphdr->dest) == 53 && !args->fwd53)
        return 1;

    // Search session
    struct udp_session *cur = udp_session;
    while (cur != NULL &&
           !(cur->version == version &&
             cur->source == udphdr->source && cur->dest == udphdr->dest &&
             (version == 4 ? cur->saddr.ip4 == ip4->saddr &&
                             cur->daddr.ip4 == ip4->daddr
                           : memcmp(&cur->saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                             memcmp(&cur->daddr.ip6, &ip6->ip6_dst, 16) == 0)))
        cur = cur->next;

    return (cur != NULL);
}

void block_udp(const struct arguments *args,
               const uint8_t *pkt, size_t length,
               const uint8_t *payload,
               int uid) {
    // Get headers
    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct udphdr *udphdr = (struct udphdr *) payload;

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (version == 4) {
        inet_ntop(AF_INET, &ip4->saddr, source, sizeof(source));
        inet_ntop(AF_INET, &ip4->daddr, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &ip6->ip6_src, source, sizeof(source));
        inet_ntop(AF_INET6, &ip6->ip6_dst, dest, sizeof(dest));
    }

    log_android(ANDROID_LOG_INFO, "UDP blocked session from %s/%u to %s/%u",
                source, ntohs(udphdr->source), dest, ntohs(udphdr->dest));

    // Register session
    struct udp_session *u = malloc(sizeof(struct udp_session));
    u->time = time(NULL);
    u->uid = uid;
    u->version = version;

    if (version == 4) {
        u->saddr.ip4 = (__be32) ip4->saddr;
        u->daddr.ip4 = (__be32) ip4->daddr;
    } else {
        memcpy(&u->saddr.ip6, &ip6->ip6_src, 16);
        memcpy(&u->daddr.ip6, &ip6->ip6_dst, 16);
    }

    u->source = udphdr->source;
    u->dest = udphdr->dest;
    u->state = UDP_BLOCKED;
    u->socket = -1;

    u->next = udp_session;
    udp_session = u;
}

jboolean handle_udp(const struct arguments *args,
                    const uint8_t *pkt, size_t length,
                    const uint8_t *payload,
                    int uid, struct allowed *redirect) {
    // Get headers
    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct udphdr *udphdr = (struct udphdr *) payload;
    const uint8_t *data = payload + sizeof(struct udphdr);
    const size_t datalen = length - (data - pkt);

    // Search session
    struct udp_session *cur = udp_session;
    while (cur != NULL &&
           !(cur->version == version &&
             cur->source == udphdr->source && cur->dest == udphdr->dest &&
             (version == 4 ? cur->saddr.ip4 == ip4->saddr &&
                             cur->daddr.ip4 == ip4->daddr
                           : memcmp(&cur->saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                             memcmp(&cur->daddr.ip6, &ip6->ip6_dst, 16) == 0)))
        cur = cur->next;

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (version == 4) {
        inet_ntop(AF_INET, &ip4->saddr, source, sizeof(source));
        inet_ntop(AF_INET, &ip4->daddr, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &ip6->ip6_src, source, sizeof(source));
        inet_ntop(AF_INET6, &ip6->ip6_dst, dest, sizeof(dest));
    }

    if (cur != NULL && cur->state != UDP_ACTIVE) {
        log_android(ANDROID_LOG_INFO, "UDP ignore session from %s/%u to %s/%u state %d",
                    source, ntohs(udphdr->source), dest, ntohs(udphdr->dest), cur->state);
        return 0;
    }

    // Create new session if needed
    if (cur == NULL) {
        log_android(ANDROID_LOG_INFO, "UDP new session from %s/%u to %s/%u",
                    source, ntohs(udphdr->source), dest, ntohs(udphdr->dest));

        // Register session
        struct udp_session *u = malloc(sizeof(struct udp_session));
        u->time = time(NULL);
        u->uid = uid;
        u->version = version;

        int rversion;
        if (redirect == NULL)
            rversion = u->version;
        else
            rversion = (strstr(redirect->raddr, ":") == NULL ? 4 : 6);
        u->mss = (uint16_t) (rversion == 4 ? UDP4_MAXMSG : UDP6_MAXMSG);

        u->sent = 0;
        u->received = 0;

        if (version == 4) {
            u->saddr.ip4 = (__be32) ip4->saddr;
            u->daddr.ip4 = (__be32) ip4->daddr;
        } else {
            memcpy(&u->saddr.ip6, &ip6->ip6_src, 16);
            memcpy(&u->daddr.ip6, &ip6->ip6_dst, 16);
        }

        u->source = udphdr->source;
        u->dest = udphdr->dest;
        u->state = UDP_ACTIVE;
        u->next = NULL;

        // Open UDP socket
        u->socket = open_udp_socket(args, u, redirect);
        if (u->socket < 0) {
            free(u);
            return 0;
        }

        log_android(ANDROID_LOG_DEBUG, "UDP socket %d", u->socket);

        u->next = udp_session;
        udp_session = u;

        cur = u;
    }

    // Check for DNS
    if (ntohs(udphdr->dest) == 53) {
        char qname[DNS_QNAME_MAX + 1];
        uint16_t qtype;
        uint16_t qclass;
        if (get_dns_query(args, cur, data, datalen, &qtype, &qclass, qname) >= 0) {
            log_android(ANDROID_LOG_DEBUG,
                        "DNS query qtype %d qclass %d name %s",
                        qtype, qclass, qname);

            if (check_domain(args, cur, data, datalen, qclass, qtype, qname)) {
                // Log qname
                char name[DNS_QNAME_MAX + 40 + 1];
                sprintf(name, "qtype %d qname %s", qtype, qname);
                jobject objPacket = create_packet(
                        args, version, IPPROTO_UDP, "",
                        source, ntohs(cur->source), dest, ntohs(cur->dest),
                        name, 0, 0);
                log_packet(args, objPacket);

                // Session done
                cur->state = UDP_FINISHING;
                return 0;
            }
        }
    }

    // Check for DHCP (tethering)
    if (ntohs(udphdr->source) == 68 || ntohs(udphdr->dest) == 67) {
        if (check_dhcp(args, cur, data, datalen) >= 0)
            return 1;
    }

    log_android(ANDROID_LOG_INFO, "UDP forward from tun %s/%u to %s/%u data %d",
                source, ntohs(udphdr->source), dest, ntohs(udphdr->dest), datalen);

    cur->time = time(NULL);

    int rversion;
    struct sockaddr_in addr4;
    struct sockaddr_in6 addr6;
    if (redirect == NULL) {
        rversion = cur->version;
        if (cur->version == 4) {
            addr4.sin_family = AF_INET;
            addr4.sin_addr.s_addr = (__be32) cur->daddr.ip4;
            addr4.sin_port = cur->dest;
        } else {
            addr6.sin6_family = AF_INET6;
            memcpy(&addr6.sin6_addr, &cur->daddr.ip6, 16);
            addr6.sin6_port = cur->dest;
        }
    } else {
        rversion = (strstr(redirect->raddr, ":") == NULL ? 4 : 6);
        log_android(ANDROID_LOG_WARN, "UDP%d redirect to %s/%u",
                    rversion, redirect->raddr, redirect->rport);

        if (rversion == 4) {
            addr4.sin_family = AF_INET;
            inet_pton(AF_INET, redirect->raddr, &addr4.sin_addr);
            addr4.sin_port = htons(redirect->rport);
        }
        else {
            addr6.sin6_family = AF_INET6;
            inet_pton(AF_INET6, redirect->raddr, &addr6.sin6_addr);
            addr6.sin6_port = htons(redirect->rport);
        }
    }

    if (sendto(cur->socket, data, (socklen_t) datalen, MSG_NOSIGNAL,
               (const struct sockaddr *) (rversion == 4 ? &addr4 : &addr6),
               (socklen_t) (rversion == 4 ? sizeof(addr4) : sizeof(addr6))) != datalen) {
        log_android(ANDROID_LOG_ERROR, "UDP sendto error %d: %s", errno, strerror(errno));
        if (errno != EINTR && errno != EAGAIN) {
            cur->state = UDP_FINISHING;
            return 0;
        }
    }
    else
        cur->sent += datalen;

    return 1;
}

int open_udp_socket(const struct arguments *args,
                    const struct udp_session *cur, const struct allowed *redirect) {
    int sock;
    int version;
    if (redirect == NULL)
        version = cur->version;
    else
        version = (strstr(redirect->raddr, ":") == NULL ? 4 : 6);

    // Get UDP socket
    sock = socket(version == 4 ? PF_INET : PF_INET6, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        log_android(ANDROID_LOG_ERROR, "UDP socket error %d: %s", errno, strerror(errno));
        return -1;
    }

    // Protect socket
    if (protect_socket(args, sock) < 0)
        return -1;

    // Check for broadcast/multicast
    if (cur->version == 4) {
        uint32_t broadcast4 = INADDR_BROADCAST;
        if (memcmp(&cur->daddr.ip4, &broadcast4, sizeof(broadcast4)) == 0) {
            log_android(ANDROID_LOG_WARN, "UDP4 broadcast");
            int on = 1;
            if (setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &on, sizeof(on)))
                log_android(ANDROID_LOG_ERROR, "UDP setsockopt SO_BROADCAST error %d: %s",
                            errno, strerror(errno));
        }
    } else {
        // http://man7.org/linux/man-pages/man7/ipv6.7.html
        if (*((uint8_t *) &cur->daddr.ip6) == 0xFF) {
            log_android(ANDROID_LOG_WARN, "UDP6 broadcast");

            int loop = 1; // true
            if (setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &loop, sizeof(loop)))
                log_android(ANDROID_LOG_ERROR,
                            "UDP setsockopt IPV6_MULTICAST_LOOP error %d: %s",
                            errno, strerror(errno));

            int ttl = -1; // route default
            if (setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &ttl, sizeof(ttl)))
                log_android(ANDROID_LOG_ERROR,
                            "UDP setsockopt IPV6_MULTICAST_HOPS error %d: %s",
                            errno, strerror(errno));

            struct ipv6_mreq mreq6;
            memcpy(&mreq6.ipv6mr_multiaddr, &cur->daddr.ip6, sizeof(struct in6_addr));
            mreq6.ipv6mr_interface = INADDR_ANY;
            if (setsockopt(sock, IPPROTO_IPV6, IPV6_ADD_MEMBERSHIP, &mreq6, sizeof(mreq6)))
                log_android(ANDROID_LOG_ERROR,
                            "UDP setsockopt IPV6_ADD_MEMBERSHIP error %d: %s",
                            errno, strerror(errno));
        }
    }

    return sock;
}

ssize_t write_udp(const struct arguments *args, const struct udp_session *cur,
                  uint8_t *data, size_t datalen) {
    size_t len;
    u_int8_t *buffer;
    struct udphdr *udp;
    uint16_t csum;
    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];

    // Build packet
    if (cur->version == 4) {
        len = sizeof(struct iphdr) + sizeof(struct udphdr) + datalen;
        buffer = malloc(len);
        struct iphdr *ip4 = (struct iphdr *) buffer;
        udp = (struct udphdr *) (buffer + sizeof(struct iphdr));
        if (datalen)
            memcpy(buffer + sizeof(struct iphdr) + sizeof(struct udphdr), data, datalen);

        // Build IP4 header
        memset(ip4, 0, sizeof(struct iphdr));
        ip4->version = 4;
        ip4->ihl = sizeof(struct iphdr) >> 2;
        ip4->tot_len = htons(len);
        ip4->ttl = IPDEFTTL;
        ip4->protocol = IPPROTO_UDP;
        ip4->saddr = cur->daddr.ip4;
        ip4->daddr = cur->saddr.ip4;

        // Calculate IP4 checksum
        ip4->check = ~calc_checksum(0, (uint8_t *) ip4, sizeof(struct iphdr));

        // Calculate UDP4 checksum
        struct ippseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ippseudo));
        pseudo.ippseudo_src.s_addr = (__be32) ip4->saddr;
        pseudo.ippseudo_dst.s_addr = (__be32) ip4->daddr;
        pseudo.ippseudo_p = ip4->protocol;
        pseudo.ippseudo_len = htons(sizeof(struct udphdr) + datalen);

        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ippseudo));
    }
    else {
        len = sizeof(struct ip6_hdr) + sizeof(struct udphdr) + datalen;
        buffer = malloc(len);
        struct ip6_hdr *ip6 = (struct ip6_hdr *) buffer;
        udp = (struct udphdr *) (buffer + sizeof(struct ip6_hdr));
        if (datalen)
            memcpy(buffer + sizeof(struct ip6_hdr) + sizeof(struct udphdr), data, datalen);

        // Build IP6 header
        memset(ip6, 0, sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_flow = 0;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_plen = htons(len - sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt = IPPROTO_UDP;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_hlim = IPDEFTTL;
        ip6->ip6_ctlun.ip6_un2_vfc = IPV6_VERSION;
        memcpy(&(ip6->ip6_src), &cur->daddr.ip6, 16);
        memcpy(&(ip6->ip6_dst), &cur->saddr.ip6, 16);

        // Calculate UDP6 checksum
        struct ip6_hdr_pseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ip6_hdr_pseudo));
        memcpy(&pseudo.ip6ph_src, &ip6->ip6_dst, 16);
        memcpy(&pseudo.ip6ph_dst, &ip6->ip6_src, 16);
        pseudo.ip6ph_len = ip6->ip6_ctlun.ip6_un1.ip6_un1_plen;
        pseudo.ip6ph_nxt = ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt;

        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ip6_hdr_pseudo));
    }

    // Build UDP header
    memset(udp, 0, sizeof(struct udphdr));
    udp->source = cur->dest;
    udp->dest = cur->source;
    udp->len = htons(sizeof(struct udphdr) + datalen);

    // Continue checksum
    csum = calc_checksum(csum, (uint8_t *) udp, sizeof(struct udphdr));
    csum = calc_checksum(csum, data, datalen);
    udp->check = ~csum;

    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6,
              cur->version == 4 ? &cur->saddr.ip4 : &cur->saddr.ip6, source, sizeof(source));
    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6,
              cur->version == 4 ? &cur->daddr.ip4 : &cur->daddr.ip6, dest, sizeof(dest));

    // Send packet
    log_android(ANDROID_LOG_DEBUG,
                "UDP sending to tun %d from %s/%u to %s/%u data %u",
                args->tun, dest, ntohs(cur->dest), source, ntohs(cur->source), len);

    ssize_t res = write(args->tun, buffer, len);

    // Write PCAP record
    if (res >= 0) {
        if (pcap_file != NULL)
            write_pcap_rec(buffer, (size_t) res);
    }
    else
        log_android(ANDROID_LOG_WARN, "UDP write error %d: %s", errno, strerror(errno));

    free(buffer);

    if (res != len) {
        log_android(ANDROID_LOG_ERROR, "write %d/%d", res, len);
        return -1;
    }

    return res;
}
