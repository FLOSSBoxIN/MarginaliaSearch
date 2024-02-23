package nu.marginalia.executor.model;

public record ActorRunState(String name,
                            String state,
                            String actorDescription,
                            String stateDescription,
                            boolean terminal,
                            boolean canStart) {

    public boolean isDaemon() {
        return name.startsWith("PROC_")
                || name.startsWith("MONITOR_");
    }

    public String stateIcon() {
        if (terminal) {
            return "\uD83D\uDC80"; // Unicode Skull
        }
        else if (state.equals("MONITOR")) {
            return "\uD83D\uDD26";
        }
        else if (state.endsWith("WAIT") || state.endsWith("REPLY")) {
            return "\uD83D\uDD59";
        }
        else {
            return "\uD83C\uDFC3";
        }
    }
}
