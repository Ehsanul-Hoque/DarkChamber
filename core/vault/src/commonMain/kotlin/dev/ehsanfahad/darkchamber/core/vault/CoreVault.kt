package dev.ehsanfahad.darkchamber.core.vault

/**
 * Module marker for `:core:vault`.
 *
 * Placeholder so the module compiles across all targets and the smoke test has something to
 * assert (see docs/engineering/MODULES.md §1). The real API — `VaultObjects` streaming
 * import/open/openMeta/shred — lands in cycle 5c (TASK-004+); delete this then.
 */
const val CORE_VAULT_MODULE: String = "core:vault"
