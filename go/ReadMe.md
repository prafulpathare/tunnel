# tunnel in GO

### Server (VPC/EC2)

```sh
go mod init tunnelserver

go get github.com/gorilla/websocket

go build -o tunnel-server .

./tunnel-server
# server listens on :8080
```

### Client (local machine)

```sh
go mod init tunnelclient

go get github.com/gorilla/websocket

go build -o tunnel-client .

tunnel-client.exe -server ws://3.110.158.162:8080/ws -token mydemo -target http://127.0.0.1:3000
```

visit http://3.110.158.162:8080/mydemo

# 
### Network Security
allow Custom TCP on 8080 with ipv4
#

### Load Tests

| ec2 (t2.micro)                |                |
|-----------------|----------------|
Concurrency Level | 100
Time taken for tests | 119.869 seconds
Complete requests | 10000
Requests per second | 83.42 [#/sec] (mean)
Time per request | 1198.688 [ms] (mean) 

#

[Ref](https://chatgpt.com/share/692d602e-beec-8000-9ea0-3d0805efe015)