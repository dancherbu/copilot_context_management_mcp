# Branch Protection Setup

Use these settings on GitHub for the main branch.

## Recommended Rules For main

1. Require a pull request before merging
2. Require approvals: 1 or 2
3. Dismiss stale pull request approvals when new commits are pushed
4. Require status checks to pass before merging
5. Require branches to be up to date before merging
6. Require conversation resolution before merging
7. Restrict who can push to matching branches
8. Do not allow force pushes
9. Do not allow deletions

## Suggested Required Checks

Add your CI workflow check names here once configured.

Example placeholders:

- build
- test
- integration

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
