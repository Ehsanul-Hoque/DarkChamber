package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * What a running import is doing, in terms a screen can say out loud.
 *
 * Three of these correspond to the vault's own `ImportPhase`, which reports *which read* is
 * happening. This is the same information in the vocabulary the user gets, and it is declared here
 * rather than reused from the vault deliberately: the vault owns no UI words, and [FETCHING] has no
 * counterpart there at all — the wait it names happens **before** the vault is called, in the
 * abandonable open the import use case performs.
 *
 * Whether a stage carries a fraction is the whole reason it exists:
 *
 * | stage        | fraction                | what the wait is                            |
 * |--------------|-------------------------|---------------------------------------------|
 * | [FETCHING]   | none                    | the platform has not handed over the file   |
 * | [COPYING]    | the payload size        | a one-shot source draining into scratch     |
 * | [ENCRYPTING] | twice the payload size  | the container's two passes                  |
 * | [FINISHING]  | none                    | making the preview                          |
 *
 * The two without a fraction are the two that used to look like nothing was happening — one before
 * any byte existed, the other after the bar had already reached its own end.
 */
enum class ImportStage {
    /**
     * Waiting for the platform to hand over the file.
     *
     * A local file passes through this in microseconds. A cloud document may sit here for minutes
     * while the provider downloads it, reporting nothing — no Android API exposes a provider's
     * download progress, so there is no number to show at any price. What can be said is that we are
     * in it, which is why this stage exists rather than an indeterminate bar with no explanation.
     */
    FETCHING,

    /**
     * Copying a payload that can only be read once into app-private scratch.
     *
     * Only some sources need this, so most imports never report it. For a provider that streams
     * rather than caches, this copy is the whole transfer — which is why it is counted.
     */
    COPYING,

    /** Encrypting the payload into the vault. The ordinary case, and the bulk of every import. */
    ENCRYPTING,

    /**
     * Making the preview, once the file itself is safely in.
     *
     * Unmeasurable by design: how many passes the encoder makes over a payload is its own business.
     * Every import passes through this, including the ones that get no preview, because whether one
     * is possible is something only the encoder knows.
     */
    FINISHING,
    ;

    /**
     * How many times the payload is read during this stage, or `null` when there is no total to
     * divide by.
     *
     * The one place the divisors are written down, so the table above cannot drift from the
     * arithmetic and [measurable] cannot disagree with whether a fraction actually comes out. A
     * `when` over the entries rather than a predicate, so a stage added later has to answer this
     * question instead of silently inheriting someone else's denominator.
     */
    internal val passes: Int?
        get() = when (this) {
            COPYING -> 1
            ENCRYPTING -> 2
            FETCHING, FINISHING -> null
        }

    /**
     * Whether this stage has a total to divide by.
     *
     * The two that do not are exactly the two a screen has to **name**, because a bar that cannot
     * move explains nothing on its own. They are also the two that must not be named too eagerly: a
     * local file passes through both in less time than a frame, so a batch of them would strobe.
     */
    val measurable: Boolean get() = passes != null
}
