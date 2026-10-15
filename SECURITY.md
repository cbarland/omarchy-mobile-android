# Security reports

Use this repository's **Security → Advisories → Report a vulnerability** option
for private reports when GitHub private vulnerability reporting is enabled.
GitHub documents the process in [Privately reporting a security vulnerability](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately).
If the option is unavailable, open an issue asking maintainers to enable a private
reporting channel, without publishing exploit details or sensitive data.

Include the affected commit/build, Android version and ABI, required permissions
or Shizuku state, a minimal reproduction, and the demonstrated impact. Remove
credentials and personal device data. Do not attach signing keys or private
rootfs images.

Relevant boundaries include archive extraction and activation, the application
UID command broker, app-owned display leases, and the separately user-authorized
Shizuku shell service. A Linux guest's apparent root identity does not grant
Android root. Ordinary compatibility failures can use the public bug template.

The project is a development prototype. There is no published stable security
support window or guaranteed response time; report the exact revision so a fix
can be evaluated against current source. Repository administrators must enable
GitHub private reporting when the repository is published.
