Run the skill locally (Node)

1. Ensure you're on the feature branch:

```powershell
cd C:\Users\user\agents\gallery\skills\featured\foog808-agent-ensemble
```

2. Run validation (this will invoke the Node harness and write `.last_run.json`):

```powershell
npm run validate
```

3. Run assertions:

```powershell
npm test
```

If CI is configured it will also run `npm run validate` as part of the workflow.
