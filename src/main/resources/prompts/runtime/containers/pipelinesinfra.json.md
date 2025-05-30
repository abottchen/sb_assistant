The JSON output of `podman inspect` for the pipelinesinfra container.

Key things to look for:
- It should be in the running state
- If it has been OOMKilled, it may be that the JVM_ARGS have allotted too little memory
- If it is not running, the exitcode might give an indication as to why
