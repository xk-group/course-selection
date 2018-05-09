ROLES="$1"
HOSTNAME="$2"
PORT="$3"
SEED1_HOST="$4"
SEED1_PORT="$5"
HTTP_PORT="$6"

if [ "$6" == "" ]; then
    echo "$0 '[\"course\"]' 10.0.0.1 2552 10.0.0.2 2552 10.0.0.3 2552 7000"
    exit 0
fi

cat <<EOF
akka {
  actor {
    provider = cluster
    serializers {
      java = "akka.serialization.JavaSerializer"
      kryo = "com.twitter.chill.akka.AkkaSerializer"
    }
    serialization-bindings {
      "moe.taiho.course_selection.KryoSerializable" = kryo
    }
  }
  remote {
    log-remote-lifecycle-events = off
    maximum-payload-bytes = 67108864 bytes
    netty.tcp {
      port = 0
      message-frame-size =  67108864b
      send-buffer-size =  67108864b
      receive-buffer-size =  67108864b
      maximum-frame-size = 67108864b
    }
  }
  cluster {
    sharding {
      buffer-size = 1000000
    }
  }
  persistence {
    journal.plugin = "akka.persistence.journal.leveldb"
    journal.leveldb.fsync = off
    snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    at-least-once-delivery {
      redeliver-interval = 5s
      redelivery-burst-limit = 10000
      warn-after-number-of-unconfirmed-attempts = 5
      max-unconfirmed-messages = 100000
    }
  }
  extensions = [
    "akka.cluster.metrics.ClusterMetricsExtension"
  ]
}
# Disable legacy metrics in akka-cluster.
akka.cluster.metrics.enabled=off
# Sigar native library extract location during tests.
# Note: use per-jvm-instance folder when running multiple jvm on one host.
akka.cluster.metrics.native-library-extract-folder=\${user.dir}/target/native
akka.cluster.roles = ${ROLES}
akka.cluster.seed-nodes = [
  "akka.tcp://CourseSelectSystem@${SEED1_HOST}:${SEED1_PORT}",
]
akka.remote.netty.tcp.hostname = "${HOSTNAME}"
akka.remote.netty.tcp.bind-hostname = "0.0.0.0"
akka.remote.netty.tcp.port = ${PORT}
akka.remote.netty.tcp.bind-port = ${PORT}
course-selection {
  student-shard-nr = 200
  course-shard-nr = 100
  http-port = $HTTP_PORT
}
EOF
