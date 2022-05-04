# Docs

gpshub is a dedicated webserver that listens on a public interface/port for incoming http://<host>:<port>/gps PUT messages
with an application/json body containing gps position update requests.

## Examples
start server:
```sh
gpshub -i 192.168.1.134 --in-port 8087 -v
```


### simulate device from command line
send from a node within network:
```sh
wget --method="PUT" --header='Content-Type: application/json' --body-data='{"id":12345,"date":1648400674,"lat":37.123,"lon":-120.56,"alt":60.01,"acc":20.5,"org":1,"role":2,"status":0}'  http://192.168.1.134:8087/gps
```

### GPSLogger for Android devices

