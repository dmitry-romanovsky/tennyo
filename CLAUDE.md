@AGENTS.md

## Claude Code setup

Project skills live in `.agents/skills/` (shared with other agents). Claude Code discovers skills only under
`.claude/skills/`, which is committed as a symlink to `../.agents/skills`.

macOS and Linux need nothing. On Windows the symlink checks out as a real link only with Developer Mode on
and `git config core.symlinks true`; otherwise it becomes a small text file. In that case replace it with a
junction once per clone:

```bash
git update-index --skip-worktree .claude/skills
rm .claude/skills
cmd //c "mklink /J .claude\skills .agents\skills"
printf '/.claude/skills/\n' >> .git/info/exclude
```
