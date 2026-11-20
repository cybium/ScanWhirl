package com.scanwhirl.app.ui.shell

/**
 * T1 owner-approved 2026-11-20: no hidden tools.
 *
 * The Tools tab used to show three rows per job behind an "All N tools" door, so tests that
 * needed the whole catalogue opened every door first. Every job now lists all of its tools
 * directly and the door is gone, so there is nothing to open. The helper stays as a no-op so
 * those tests keep reading as "the whole catalogue is reachable" without a second pattern.
 */
@Suppress("UNUSED_PARAMETER")
internal fun openAllToolsDoors(activity: ShellActivity) = Unit
