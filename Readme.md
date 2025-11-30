# tunnel service

### Agenda
- save costs from AWS EC2 bills
- end-to-end control over infra (excluding tunnel server which will be managed on public shared VPC)
- hosting apps from own machine

# Setup

### AWS EC2 Setup

Launch EC2 instance (Ubuntu recommended)
Security Group - Inbound Rules:

   - Port 80 (HTTP) - Source: 0.0.0.0/0
   - Port 9000 (Tunnel) - Source: 0.0.0.0/0
   - Port 22 (SSH) - Source: Your IP

##### Deploy server

```sh  
# SSH into EC2
ssh -i your-key.pem ubuntu@<EC2-PUBLIC-IP>

# Install Java
sudo apt update
sudo apt install default-jdk -y

# Upload and compile
nano TunnelServer.java  # paste the code
javac TunnelServer.java

# Run (needs sudo for port 80)
sudo java TunnelServer
```

##### Run locally

```sh
# Compile
javac TunnelClient.java

# Edit SERVER_HOST to your EC2 IP
# Run with custom tunnel ID
java TunnelClient myapp 8080
```
Your local webapp on localhost:8080 will now be accessible at:
http://`<EC2-PUBLIC-IP>`/myapp


## Troubleshoot

> Check EC2 Security Group (Most Common Issue)

Go to AWS Console → EC2 → Security Groups:

Inbound Rules must have:
Type: Custom TCP\
Port: 9000\
Source: 0.0.0.0/0 (or your IP)\

Type: HTTP\
Port: 80\
Source: 0.0.0.0/0

> Verify Server is Running

SSH into your EC2 and check\
Check if server is running
```sh
sudo netstat -tulpn | grep java
```
Should show:
```sh
tcp6  0  0 :::80      :::*  LISTEN  <pid>/java
tcp6  0  0 :::9000    :::*  LISTEN  <pid>/java
```
If not running, start it:
```sh
sudo java TunnelServer
```
> Update CLIENT with Correct EC2 IP

In TunnelClient.java, change this line:
```java
private static final String SERVER_HOST = "YOUR-EC2-PUBLIC-IP"; // e.g., "3.15.123.45"
```
Find your EC2 Public IP:\
AWS Console → EC2 → Instances → Your Instance → Public IPv4 address

> Test Connection Manually from your local machine

Test if port 9000 is reachable
```sh 
telnet YOUR-EC2-IP 9000
```
##### Or use curl
curl http://YOUR-EC2-IP

> Check EC2 Network ACLs

AWS Console → VPC → Network ACLs → Make sure inbound/outbound rules allow traffic

## References
[claude.ai - open loalhost to www](https://claude.ai/public/artifacts/5a5a74c1-9752-43b3-9585-d186198f1696)