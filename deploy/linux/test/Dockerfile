# A throwaway "VPS": Debian 12 booting systemd, to run deploy/linux/install.sh for real.
FROM debian:12
RUN apt-get update -q && apt-get install -y -q systemd systemd-sysv curl ca-certificates gnupg procps && \
    rm -f /lib/systemd/system/multi-user.target.wants/getty* && apt-get clean
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
