#!/bin/bash
# usage: ./run_all.sh <n> <r> <k> <host> <port>
# Example: ./run_all.sh 3 5 1 localhost 5000

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
  java ClientProcess $i $HOST $PORT $R $K &
  # iniciamos em background; "sem retardo" definido no enunciado: iniciados sequencialmente mas sem sleep
done

echo "Started $N processes."
