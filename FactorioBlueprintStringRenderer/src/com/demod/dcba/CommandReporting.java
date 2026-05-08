package com.demod.dcba;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Replay-analyzer fork: standalone replacement for the
// Discord-Core-Bot-Apple class of the same name. The upstream version
// captured render warnings/exceptions for posting back to a Discord
// channel; this fork only logs them and keeps a list, so callers
// inside FBSR can keep using the existing addException(...) API
// without pulling DCBA into the dependency graph.
public class CommandReporting {
	private static final Logger LOGGER = LoggerFactory.getLogger(CommandReporting.class);

	private final List<Throwable> exceptions = new ArrayList<>();

	public CommandReporting(Object unusedAuthor, Object unusedContext, Object unusedMessage) {
		// Three-arg signature matches the upstream constructor; the args were
		// Discord-author / context / source-message metadata, irrelevant here.
	}

	public void addException(Throwable t) {
		exceptions.add(t);
		LOGGER.warn("FBSR render exception", t);
	}

	public void addException(Throwable t, String blame) {
		exceptions.add(t);
		LOGGER.warn("FBSR render exception ({}): {}", blame, t.toString(), t);
	}

	public List<Throwable> getExceptions() {
		return exceptions;
	}
}
