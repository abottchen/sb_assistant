Whenever a question is related to the health of CD4PE or the status of the application, this is the primary file to
look at. When providing information on the container status, always provide information on all 4 containers: query, ui,
pipelinesinfra, and postgres.

IMPORTANT NOTE:
The query container may not have a "COMMAND" in the output. This is normal. If you think it is not running, check
the `runtime/containers/query.json` file to make sure.

Should show 4 running containers, one for pipelinesinfra, postgres, ui, and query:

CONTAINER ID  IMAGE                                                                                       COMMAND               CREATED        STATUS        PORTS                                                  NAMES
ffab623b8053  gcr.io/platform-services-297419/cd4pe-postgresql:latest                                     /opt/bitnami/scri...  9 minutes ago  Up 9 minutes  0.0.0.0:5432->5432/tcp                                 postgres
d03ead77698c  gcr.io/platform-services-297419/teams-ui:latest                                             /bin/bash -c /etc...  9 minutes ago  Up 9 minutes  0.0.0.0:443->3000/tcp, 0.0.0.0:8000->8000/tcp, 80/tcp  ui
14feee325f42  gcr.io/platform-services-297419/cd4pe-dev/continuous-delivery-for-puppet-enterprise:latest  com.puppet.pipeli...  9 minutes ago  Up 9 minutes  0.0.0.0:8080->8080/tcp, 0.0.0.0:8800->8000/tcp         pipelinesinfra
3929fb8c5d29  gcr.io/platform-services-297419/query-service:latest                                                              9 minutes ago  Up 9 minutes  0.0.0.0:8180->8080/tcp, 8888/tcp                       query

All four containers must be in a running state and configured with the ports from this example.

If there are port descrepancies or if a container is missing or not in a running state, that would indicate that there
is a failure.  If a container is missing or looks to not be running, look at the corresponding file for that service
in `runtime/containers/*.json` for more information. 

If the pipelinesinfra container is not running, analyze the `logs/backend/pipelinesinfra/*` files for more information
on the failure.
If the query container is not running, analyze the `logs/backend/query/*` files for more information
on the failure.
If the postgres container is not running, analyze the `logs/database.postgres/*` files for more information
on the failure.
If the pipelinesinfra container is not running, analyze the `logs/ui.ui/*` files for more information
on the failure.
