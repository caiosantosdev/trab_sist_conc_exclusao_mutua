#!/bin/bash
# usage: ./run_all.sh <n> <r> <k> <host> <port>

N=$1
R=$2
K=$3
HOST=${4:-localhost}
PORT=${5:-5000}

if [ -z "$N" ] || [ -z "$R" ] || [ -z "$K" ]; then
  echo "Usage: $0 <n> <r> <k> [host] [port]"
  exit 1
fi

for (( i=1; i<=N; i++ )); do
  java -cp target/classes org.cefet.sist_conc_dist.ClientProcess $i $HOST $PORT $R $K &
done

echo "Started $N processes."