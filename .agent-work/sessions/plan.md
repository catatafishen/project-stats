# Inline Review Panel + Non-Blocking Gating

## Problem
- Review panel is only available when a git tool prompts it (hidden tab auto-adds/removes).
- `awaitReviewCompletion` blocks 5min — MCP client times out before the useful error reaches the agent.
- User wants: review panel on left of chat, collapsible, always openable; clearer gated-git message.

## Approach
1. Wrap tool-window content in `OnePixelSplitter` (horizontal). Left=`ReviewChangesPanel`, right=connect/chat card layout. Default collapsed (0.0). Draggable, double-click to collapse.
2. Title-bar toggle action expands/collapses the splitter.
3. `ReviewChangesPanel` subscribes to `ReviewSessionTopic` itself via Disposer (owns its refresh).
4. Replace `awaitReviewCompletion` blocking wait with immediate return: `checkReviewPending(op)` returns "Error: Review pending (N unreviewed changes for M files) — ..." or null. Notification fires only on transition.
5. Delete `ReviewTabManager` (tab no longer needed). Move expand-on-block into `ChatToolWindowContent` via project-service hook.
6. Update tests (ReviewGatingFutureTest → rename/rewrite for immediate-return semantics).
