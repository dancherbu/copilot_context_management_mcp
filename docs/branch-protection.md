# Branch Protection Setup

Use these settings on GitHub for the main branch.

## Recommended Rules For main

1. Require a pull request before merging
2. Require approvals: 1 or 2
3. Dismiss stale pull request approvals when new commits are pushed
4. Require conversation resolution before merging
5. Restrict who can push to matching branches
6. Do not allow force pushes
7. Do not allow deletions

## Status Checks Policy

- This repository intentionally avoids GitHub Actions to prevent CI billing surprises.
- Leave required status checks unset by default.
- Require contributors to run local validation before opening a PR.

Required local validation commands:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q test -DskipITs
docker compose up --build -d
curl -fsS http://localhost:18080/actuator/health/liveness
```

## Recommended Merge Strategy

- Enable Squash merge
- Disable Merge commit if you want a linear history

## Optional Protection Enhancements

- Require signed commits
- Require linear history
- Require successful deployments for production environments

## How To Configure (GitHub UI)

1. Open repository settings.
2. Go to Branches.
3. Under Branch protection rules, add rule for main.
4. Apply the rules listed above.
5. Save changes.

## Operational Notes

- Use short-lived feature branches.
- Keep PR scope small for easier review.
- Rebase or merge main frequently to reduce conflicts.
- Add local validation output summary in PR descriptions.
