# Stop confirmation regression checks

The change corrects client state handling; it is not a claim that the user's camera has already passed the new build.

Specific v0.2 logic problems:

1. `canControlRecording()` required `pending == null` for both actions, so a user could not request stop while start was still awaiting status. Clearing the old pending start then checking its old idle snapshot would also be unsafe: a stop must be sent explicitly when it supersedes that start.
2. Start and stop shared a five-second deadline and the pending request was discarded at timeout. A later status could update raw camera truth but could never reconcile the prior request result. The new client waits eight seconds for start and twenty for stop, then retains the same goal for thirty seconds of status-only recovery. These are conservative application time windows, not measured camera save durations.
3. A nonzero command ACK discarded the goal immediately, even if a subsequent camera status could establish that the requested stopped state had been reached. The new handler records the ACK, then verifies actual state. It reports explicit rejection if the longer window ends with a negative ACK and a fresh opposite state.
4. One post-command status notification was enough to confirm the goal. The new handler requires two matching observations at least 250 ms apart. A normal stop also waits at least 500 ms from dispatch; a stop covering an unconfirmed start waits at least one second. This reduces dependence on a single delayed or transitional snapshot; it does not manufacture timestamps missing from the camera protocol.

DJI's official 1D02 documentation defines state 0 as screen off, 1 as live view/not recording, 2 as playback, 3 as capture/record, and 5 as pre-recording. It does **not** define a file-saving state 4. Screen-off, playback, and unknown states therefore remain unknown for recording confirmation. Actual normal-video state 3 is recording; stable state 1 or 5 confirms that actual recording has stopped. Pre-recording remains distinct from actual capture.

Stop priority is a goal change, not a shutter toggle. `setRecording(false,id)` can replace an unconfirmed start. The old start's later ACK or result can no longer override that goal. The host transport must cancel any not-yet-sent start and serialize the explicit stop behind an already in-flight write. The session never automatically retransmits a start or stop. Repeated stop request IDs can share one outstanding wire command and each receive the final result.

The host should expose `canStartRecording()` and `canStopRecording()` separately; stop can be available while start is pending. `getPendingDesired()` exposes an outstanding or recovering goal; `isBusy()` distinguishes the active 8/20-second window from status-only late recovery; `getCommandPhase()` is `idle`, `starting`, `stopping`, or `recovering`. `getRevision()` increases as session state advances. The host still needs a separate monotonic phone-to-watch snapshot sequence to reject out-of-order messages.

If status pushes stall during a pending/recovering operation, a bounded repeat of the documented 2 Hz subscription is issued no more often than every two seconds. This only recovers status; it does not resend a recording command.

Run the JVM test with `DjiProtocol.java`, `DjiSession.java`, and `tests/cn/local/actionrelay/protocol/DjiSessionTransitionTest.java`. The test covers saving-like unknown transitions, waiting beyond five seconds, quick start→stop, old ACKs, pre-start idle reports, duplicate stop goals, late status reconciliation, unknown and rejected outcomes, recovery expiry, stale state, disconnection, synchronous transport failures, and an official framing golden vector. Hardware timing, camera file completion, and phone/watch delivery remain end-to-end verification tasks.
