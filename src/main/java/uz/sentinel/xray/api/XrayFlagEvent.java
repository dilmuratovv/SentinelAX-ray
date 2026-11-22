package uz.sentinel.xray.api;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread when SentinelAX-ray finds suspicious mining. Observer only: it cannot be cancelled.
 * Same getter names as SentinelAD's DupeFlagEvent, so companion plugins can read both reflectively.
 */
public final class XrayFlagEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final int confidence;
    private final String detail;
    private final boolean test;

    public XrayFlagEvent(@NotNull UUID playerId, @NotNull String playerName, int confidence, @NotNull String detail, boolean test) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.detail = detail;
        this.test = test;
    }

    public @NotNull UUID getPlayerId() { return playerId; }
    public @NotNull String getPlayerName() { return playerName; }
    /** Stable identifier of the finding kind. */
    public @NotNull String getTypeId() { return "xray-mining"; }
    public int getConfidence() { return confidence; }
    public @NotNull String getDetail() { return detail; }
    /** True for /sxray testflag; listeners should not punish. */
    public boolean isTest() { return test; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
