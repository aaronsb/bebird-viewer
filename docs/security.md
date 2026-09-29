# Security notes

- The access point is open by default, and anyone in range can join it and view the camera the same way this tool does.
- The protocol has no authentication. Anyone on the scope's network can stream, change settings, or switch it off.
- The scope reports no HTTP server of its own (`has_http: false`) and has no route to the internet unless someone configures it to join another network. This viewer never contacts anything but the scope.

See [app-analysis.md](app-analysis.md) for what the official app does on the network.

