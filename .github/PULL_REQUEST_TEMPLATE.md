## Summary

<!-- Briefly describe what changed and why. -->

## Type of Change

- [ ] Bug fix
- [ ] New feature / harness improvement
- [ ] Documentation / evaluation update
- [ ] Security or cross-platform hardening

## Verification

- [ ] `sh mvnw -B -ntp verify` (or `.\mvnw.cmd -B -ntp verify`)
- [ ] `node scripts/check-doc-links.mjs` (if docs changed)
- [ ] Cross-platform check (Windows / macOS / Linux compatibility & failure/edge cases noted below)

### Verification Notes

- **Compatibility handling**:
- **Simulated / automated tests**:
- **Physical OS verification scope**:

## Checklist

- [ ] No secrets, tokens, or personal host paths included in commits or test artifacts
- [ ] Public documentation updated (English primary + `zh-CN` where applicable)
