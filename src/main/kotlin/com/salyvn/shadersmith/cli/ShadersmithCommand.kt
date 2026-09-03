package com.salyvn.shadersmith.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.salyvn.shadersmith.contract.ContractDataset
import java.io.IOException

/**
 * What every subcommand shares: the dataset, and the rule that a user's mistake is one sentence on stderr
 * with exit code 2, while a pack that fails its check is exit code 1.
 */
abstract class ShadersmithCommand(name: String) : CliktCommand(name = name) {

    protected val dataset: ContractDataset by lazy { ContractDataset.load() }

    final override fun run() {
        try {
            execute()
        } catch (failure: IllegalArgumentException) {
            fail(failure.message)
        } catch (failure: IllegalStateException) {
            fail(failure.message)
        } catch (failure: IOException) {
            fail(failure.message)
        }
    }

    protected abstract fun execute()

    protected fun fail(message: String?): Nothing {
        echo(message ?: "The command could not be carried out.", err = true)
        throw ProgramResult(USAGE_FAILED)
    }

    protected companion object {
        /** The pack has findings at error level. */
        const val CHECK_FAILED = 1

        /** The command itself could not run: bad version, unreadable pack, bad argument. */
        const val USAGE_FAILED = 2
    }
}
