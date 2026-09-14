The repository is not currently ready for code contributions. We will
make a separate announcement when we are ready for OSS users to make
contributions to it.

## Security Requirements

To maintain a secure development environment, all GitHub Actions workflows and configuration changes are subject to security scanning using [Zizmor](https://github.com/woodruffw/zizmor).

When contributing to workflows, please ensure:
- All external GitHub Actions are pinned to a specific full-length commit SHA.
- `persist-credentials: false` is set for `actions/checkout` unless strictly required.
- Explicit permissions are defined for all jobs (avoid overly broad permissions).
- All workflows pass Zizmor security checks.
