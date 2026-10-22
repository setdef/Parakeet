package com.setdef.parakeet

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path

enum class InkoCommand(val cli: String, val label: String) {
    RUN("run", "run — compile and run a file"),
    TEST("test", "test — run the project's tests"),
    BUILD("build", "build — compile an executable"),
    CHECK("check", "check — type-check without compiling"),
}

class InkoRunConfigurationType : ConfigurationTypeBase(
    ID, "Inko", "Run Inko programs and tests", NotNullLazyValue.createValue { InkoIcons.FILE },
) {
    init {
        addFactory(InkoRunConfigurationFactory(this))
    }

    companion object {
        const val ID = "ParakeetInkoRunConfiguration"

        fun getInstance(): InkoRunConfigurationType = ConfigurationTypeUtil.findConfigurationType(InkoRunConfigurationType::class.java)
    }
}

class InkoRunConfigurationFactory(type: InkoRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "Inko"
    override fun createTemplateConfiguration(project: Project): RunConfiguration = InkoRunConfiguration(project, this, "Inko")
    override fun getOptionsClass() = InkoRunConfigurationOptions::class.java
}

class InkoRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var command by string(InkoCommand.RUN.cli)

    /** The file for run/build/check; for test, an optional filter (a test file path or a test name). */
    var target by string()
    var arguments by string()
    var workingDirectory by string()
    var environment by map<String, String>()
    var passParentEnvironment by property(true)
}

class InkoRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<InkoRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): InkoRunConfigurationOptions = super.getOptions() as InkoRunConfigurationOptions

    val command: InkoCommand
        get() = InkoCommand.entries.firstOrNull { it.cli == options.command } ?: InkoCommand.RUN

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = InkoRunConfigurationEditor(project)

    override fun checkConfiguration() {
        if (!InkoCli.getInstance(project).isAvailable) {
            throw RuntimeConfigurationError("No inko executable found. Choose one in Settings | Languages & Frameworks | Inko.")
        }
        if (command == InkoCommand.RUN && options.target.isNullOrBlank()) {
            throw RuntimeConfigurationError("Choose the Inko file to run.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val root = projectRoot()
        return if (command == InkoCommand.TEST && root != null) InkoTestRunState(environment, this, root) else InkoRunState(environment, this)
    }

    /** The project directory: the working directory if set, else the project root of the target file. */
    fun projectRoot(): Path? = options.workingDirectory?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: options.target?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.isAbsolute && Files.exists(it) }?.let(Inko::projectRoot)
        ?: project.basePath?.let(Path::of)
}

class InkoRunState(environment: ExecutionEnvironment, private val configuration: InkoRunConfiguration) : CommandLineState(environment) {
    private val workingDirectory: Path? = configuration.projectRoot()

    init {
        addConsoleFilters(InkoFileLinkFilter(environment.project, workingDirectory))
    }

    /** `inko <command> [target] [arguments…]`, with the configuration's environment and working directory. */
    fun commandLine(): GeneralCommandLine {
        val options = configuration.options
        val arguments = listOf(configuration.command.cli) +
            listOfNotNull(options.target?.takeIf { it.isNotBlank() }) +
            ParametersListUtil.parse(options.arguments.orEmpty())
        val command = InkoCli.getInstance(environment.project).commandLine(arguments, workingDirectory)
            ?: throw ExecutionException("No inko executable found. Choose one in Settings | Languages & Frameworks | Inko.")
        return command
            .withEnvironment(options.environment)
            .withParentEnvironmentType(
                if (options.passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                else GeneralCommandLine.ParentEnvironmentType.NONE,
            )
    }

    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(commandLine())
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
