# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| Older | No |

Security fixes are released as new versions on
[GitHub Releases](https://github.com/junopark00/hermit-android/releases).

## Reporting a vulnerability

Please do not report security vulnerabilities in public issues.

Report them privately through GitHub's private vulnerability reporting: open the repository's
**Security** tab and choose **Report a vulnerability**
(<https://github.com/junopark00/hermit-android/security/advisories/new>).

Include as much as you can:

- the affected version and the device and Android version;
- the host software and version, if relevant;
- a description of the issue and its impact;
- steps to reproduce, or a proof of concept.

You will get an acknowledgement as soon as possible. Once the issue is confirmed, a fix is prepared
and released, and the advisory is published with credit to you unless you prefer otherwise.

## Scope

This policy covers the Hermit for Android app in this repository. Vulnerabilities in the host should
be reported to [Shell](https://github.com/junopark00/hermit-shell) or to the respective host project.
Vulnerabilities in the streaming core (`moonlight-common-c`) or the other third-party components
listed in [NOTICE](NOTICE) should also be reported to their upstream projects.
