package club.rainbowkitty.polytrains.link;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/** Which of its two jobs a player's wrench is set to do to a cart it is used on. */
public enum WrenchMode implements StringRepresentable {

    /** Couples two carts, spending a chain out of the player's pack. */
    LINK("link"),

    /** Breaks the one coupling between two carts, handing the chain that held it back. */
    UNLINK("unlink");

    /** Persistence codec for this mode. */
    public static final Codec<WrenchMode> CODEC = StringRepresentable.fromEnum(WrenchMode::values);

    // The string this mode (de)serializes as, independent of the enum constant's own name.
    private final String serializedName;

    // Binds the serialized name to the constant.
    private WrenchMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /** The job the wrench moves to when the player switches it. */
    public WrenchMode next() {
        return this == LINK ? UNLINK : LINK;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }
}
