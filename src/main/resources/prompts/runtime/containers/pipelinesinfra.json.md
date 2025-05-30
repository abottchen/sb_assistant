The JSON output of `podman inspect` for the pipelinesinfra container.

Key things to look for:
- It should be in the running state
- If it has been OOMKilled, it may be that the JVM_ARGS have allotted too little memory
- If it is not running, the exitcode might give an indication as to why
- The JVM_ARGS environment variable sets the amount of memory allocated to the java heap for CD4PE. It is expected
  to be at least 1GiB
- It is normal for many of the environment variables to be set to "null", as this reflects data that was redacted.
