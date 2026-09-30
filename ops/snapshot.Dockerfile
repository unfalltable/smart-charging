FROM postgres:18-alpine
# GNU tar preserves POSIX ACLs used to let UID 10001 read private bind mounts.
RUN apk add --no-cache tar acl && tar --version | grep 'GNU tar'
ENTRYPOINT ["tar"]
