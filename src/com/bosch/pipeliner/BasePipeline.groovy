package com.bosch.pipeliner


abstract class BasePipeline implements Serializable {
   
    /**
     * The input keys and their default values for the pipeline in String
     */
    protected String defaultInputs = ''
    /**
     * The input keys, that are exposed for all inherited pipelines
     */
    protected List<String> alwaysExposed = ['labels', 'timeout', 'dockerArgs', 'customdockerfilesource', 'dockerimage']
    /**
     * The keys for which pipeline should be parallelized
     */
    protected List parallel = []
    /**
     * The explicit combinations by user inputs should be parallelized, this will
     * overwrite the auto generated combinations by generateStageInputs and
     * defaultCombinations.
     */
    protected List combinations = []
    /**
     * The node label expression
     */
    protected String nodeLabelExpr = ''
    /**
     * Alternative node label expression used together with altTargets
     */
    protected String altNodeLabelExpr = ''
    /**
     * Space delimited list of alternative targets that use altNodeLabelExpr
     */
    protected String altTargets = ''
    /**
     * The timeout for pipeline in Minutes
     * Default value 210 minutes = 3.5 hours
     */
    protected Integer pipelineTimeout = 210
    /**
     * The actual inputs at the runtime
     */
    protected Map inputs
    /**
     * Jenkins scripted environment reference
     */
    protected def script
    /**
     * mergeRequest message from Git
     */
    protected String message
    /**
     * Map of environment variables
     */
    protected Map env
    /**
     * The input parser object instance
     */
    protected InputParser parser
    /**
     * The utils object instance
     */
    protected ScriptUtils utils
    /**
     * Map of input-output values for pipelines
     * This will be initialized with Maps, so the variable becomes a Map of Maps
     * Raw usage:
     *     setter: ioMap[parallelName]["fieldName"] = ["fieldValue"]
     *     getter: ioMap[parallelName]["fieldName"]["fieldValue"]
     * API usage with lock-protected functions:
     *     setter: setMapValue
     *     getter: getMapValue
     */
    protected Map ioMap
    /**
     * Checkout credentials id, used for git with sshagent
     * This is read from the environment variable CHECKOUT_CREDENTIALS_ID
     */
    protected String checkoutCredentialsId
    /**
     * Current user. This variable is evaluated from environment.
     * Value is available once node is entered.
     */
    protected String currentUser
    /**
     * Current user id. This variable is evaluated from environment.
     * Value is available once node is entered.
     */
    protected String currentUserId
    /**
     * Current group id. This variable is evaluated from environment.
     * Value is available once node is entered.
     */
    protected String currentGroupId
    /**
     * Name of the docker image and tag used to build.
     * Value is available once the docker image is built.
     */
    protected String dockerImageTag = ''
    /**
     * Logger object. Needs to be dynamic to display messages after the Jenkins master restart.
     */
    protected LoggerDynamic logger

    /**
     * Constructor
     *
     * @param script Reference to the Jenkins scripted environment
     * @param defaults contains a Map of defaults
     * @param env A Map of environment variables
     * @param ioMap A Map of input-output variables
     */
    BasePipeline(def script, Map defaults, Map env, Map ioMap) {
        this.script = script
        this.env = env
        this.defaultInputs = defaults.defaultInputs
        this.parallel = defaults.parallel.collect{it.toLowerCase()}
        this.utils = new ScriptUtils(script, env)
        this.logger = new LoggerDynamic(script)
        this.parser = new InputParser(defaults.exposed + alwaysExposed, this.logger, this.parallel)
        this.ioMap = ioMap
    }

    /**
     * Initialize internal variables from values provided in the environment
     */
    void initializeFromEnvironment() {
        try {
            this.checkoutCredentialsId = this.env.CHECKOUT_CREDENTIALS_ID ? this.env.CHECKOUT_CREDENTIALS_ID : ""
            if (this.checkoutCredentialsId.isEmpty()) {
                def split = this.env.JOB_NAME.tokenize("./")
                if (split.size() > 0) {
                    //logger.info(split.inspect())
                    this.checkoutCredentialsId = split[0] + "-jenkins-ssh"
                }
                else {
                    this.checkoutCredentialsId = "global-jenkins-ssh"
                }
            }
            //logger.info("Checkout credentials: " + this.checkoutCredentialsId)
        } catch (NullPointerException) {
            this.checkoutCredentialsId = ""
        }
    }

    /**
     * Initialize variables from environment, by evaluating expressions.
     *
     * NOTE: This has to be called when we enter the node, not before
     * If this is called before, the variable can be set only in the jenkins
     * global configuration.
     */
    void evaluateFromEnvironment() {
        this.currentUser = utils.shWithStdout('echo $USER')
        this.currentUserId = utils.shWithStdout('id -u')
        this.currentGroupId = utils.shWithStdout('id -g')
    }

    void processUserInput() {
        //logger.info("****** BasePipeline:processUserInput ********")

        Map parsedUserInputs = this.parser.parse(this.defaultInputs, this.env)
        this.inputs = parsedUserInputs.args

        // Filter unwanted key, value pairs
        Map tmp = [:]
        for (key in this.inputs.keySet()) {
            // Keep key value pairs as they are if they are supposed to be paralleled
            if (this.parallel.contains(key)) {
                tmp[key] = this.inputs[key]
            } else {
                // Due to the format ["key": ["one"]], we need to fetch the
                // raw value for single key value pairs, if the key is not part
                // of parallel list and the value is only one single value
                if (this.inputs[key].size() == 1) {
                    tmp[key] = this.inputs[key][0]
                } else {
                    tmp[key] = this.inputs[key]
                }
            }
        }
        this.inputs = tmp

        //logger.info("Node label expression (labels): " + inputs["labels"])
        // Only change the label if there is any meaningful label input
        if (inputs["labels"]) {
            if (inputs["labels"].getClass() == java.util.ArrayList) {
                nodeLabelExpr = inputs["labels"].join(" ").replace("  ", " || ")
            } else {
                nodeLabelExpr = reassembleExpression(inputs["labels"])
            }
        }
        //logger.info("Node label expression: " + nodeLabelExpr)

        if (inputs["timeout"]?.trim()) {
            pipelineTimeout = inputs["timeout"].toInteger()
        }
        //logger.info("inputs:" + inputs)
    }

    /**
     * Defines the stages for a particular pipeline. This should be overridden in
     * the derived class.
     *
     * @param A Map with the inputs for stages
     */
    abstract void stages(Map stageInput)


    /**
     * Post function hook that is executed even in case earlier stages have failed
     *
     * NOTE: This is run inside of the scheduled node and workspace
     * NOTE: This is run for each parallel separately
     *
     * @param A Map with the inputs for stages
     */
    void postParallel(Map stageInput) {
        //logger.info("BasePipeline: postParallel")
    }

    /**
     * Post function hook that is executed even in case earlier stages have failed
     *
     * NOTE: This is run for each parallel separately outside node/workspace/docker
     *
     * @param A Map with the inputs for stages
     */
    void postParallelFinally(Map stageInput) {
        //logger.info("BasePipeline: postParallelFinally")
    }

    /**
     * Post function hook that is executed even in case earlier stages have failed
     *
     * NOTE: This is run outside of the scheduled node, workspace and docker
     * NOTE: This is run only once for the pipeline, not for each parallel
     *
     * @param A Map with the collected results from all parallel runs
     */
    void postPipeline(Map results) {
        //logger.info("BasePipeline: postPipeline")
    }

    /**
     * Pre function hook that is executed before the actual pipeline
     *
     * NOTE: This is run outside of the scheduled node, workspace and docker
     * NOTE: This is run only once for the pipeline, not for each parallel
     *
     * @param A Map with the inputs for stages
     */
    void prePipeline(Map stageInput) {
        //logger.info("BasePipeline: prePipeline")
    }

    /**
     * Pre node hook that is executed right after entering a node
     *
     * NOTE: This is run inside of the scheduled node
     *
     * @param A Map with the inputs for stages
     */
    void preNode(Map stageInput) {
        //logger.info("BasePipeline: preNode")
        script.cleanWs()
    }

    /**
     * Pre node workspace that is executed right after entering a workspace
     *
     * NOTE: This is run inside of the scheduled node and workspace
     *
     * @param A Map with the inputs for stages
     */
    void preWorkspace(Map stageInput) {
        //logger.info("BasePipeline: preWorkspace")
        script.cleanWs()
    }

    /**
     * Defines the combined behaviour with stages and post steps
     *
     * @param A Map with the inputs for stages
     * @param A boolean specifying if postParallel is run or not.
     *        true for native
     *        false for docker (see runInDocker)
     */
    void setupStages(Map stageInput, boolean isNative=true) {
        Exception exception = null
        try {
            this.stages(stageInput)
        }
        catch (Exception ex)
        {
            exception = ex
            //Since post is called before the exception, we must set the build
            //result manually in case of an exception
            if (this.script.currentBuild.currentResult != "UNSTABLE")
                this.script.currentBuild.result = "FAILURE"
        }
        finally {
            if (isNative)
                postParallel(stageInput)
            if (exception)
                throw exception
        }
    }

    /**
     * Adds a node and runs stages
     *
     * @param A Map with the inputs for stages
     * @param A String with alternative node label
     */
    void setupNodeWithStages(Map stageInput, String alternativeLabel) {
        //logger.info("Parallel name: " + stageInput["parallelName"])
        this.script.node(alternativeLabel ? alternativeLabel : this.nodeLabelExpr) {
            preNode(stageInput)
            evaluateFromEnvironment()
            this.script.ws(createWorkspaceName()) {
                preWorkspace(stageInput)
                this.utils.withSshAgent( {
                    setupStages(stageInput)
                }, this.checkoutCredentialsId)
            }
        }
    }

    /**
     * Function to do a full checkout. This is needed in case checkout is to
     * be performed before Dockerfile is to be built.
     * Support for integration tests includes usage of environment variables.
     */
    void earlyCheckout() {
        String url = this.env.CHECKOUT_URL
        String branch = this.env.CHECKOUT_BRANCH ? this.env.CHECKOUT_BRANCH : "master"
        ArrayList extensions = [
            [$class: 'SubmoduleOption',
            parentCredentials: true]
        ]

        utils.checkout(url, branch, checkoutCredentialsId, extensions)
    }
    

    /**
     * Sanitize job name
     *
     * Job names created dynamically may contain unwanted characters and
     * are possibly in unwanted format. This function makes them more
     * reasonable. For example gitlab created jobs.
     *
     * @param A job name string
     */
    String sanitizeJobName(String jobName) {
        assert jobName != null
        assert jobName != ""

        String name = jobName
        name = name.replaceAll("%2F", ".")
        name = name.replaceAll("/", ".")

        if (name.contains("!")) {
            //Job has a merge request syntax
            def left = name.tokenize("!")[0]
            def right = name.tokenize("!")[1]
            name = left.replaceAll("[^a-zA-Z0-9-.]+","_")
            name += "_mr-"

            def number = right
            if (right.contains(" ")) {
                number = right.tokenize(" ")[0]
            }
            name += number.replaceAll("[^a-zA-Z0-9-.]+","_")
        }
        else {
            //Job has generic or branch build syntax
            name = name.replaceAll("[^a-zA-Z0-9-.]+","_")
        }

        //In the case of long job name, make it fit (15 chars reserved for base+executor)
        //Total of 80
        if (name.length() > 64)
            name = name.substring(0, 64)

        // Remove trailing -._ characers
        return name.tokenize("-._").join("_")
    }

    /**
     * Create a workspace name that is not badly mangled by Jenkins/Gitlab
     */
    String createWorkspaceName() {
        String base = "workspace/"
        String name = sanitizeJobName(this.env.JOB_NAME)
        //executor prefix e<number>, always start folders with a valid character
        String workspaceName = base + "e" + this.script.EXECUTOR_NUMBER + "_" + name

        //logger.info("Workspace name: " + workspaceName)

        return workspaceName
    }

    /**
     * Initializes input-output Map for a single parallel run instance.
     *
     * @param A String with the name of the parallel to be initialized
     */
    void initValueMap(String parallelName) {
        this.script.lock("ioMap") {
            //Initialize empty map for this parallel, but do not reset it
            if (this.ioMap[parallelName] == null)
                this.ioMap[parallelName] = [:]
        }
    }

    /**
     * Gets a field value from the input-output Map for a single parallel run instance.
     *
     * @param A String with the name of the parallel
     * @param A String with the name of the field
     * @return A String with the value of the field
     */
    String getMapValue(String parallelName, String fieldName) {
        this.script.lock("ioMap") {
            return this.ioMap[parallelName][fieldName]
        }
    }

    /**
     * Sets a field value to the input-output Map for a single parallel run instance.
     *
     * @param A String with the name of the parallel
     * @param A String with the name of the field
     * @param A String with the value of the field
     */
    void setMapValue(String parallelName, String fieldName, String value) {
        this.script.lock("ioMap") {
            this.ioMap[parallelName][fieldName] = value
        }
    }

    Map runStages() {
        initializeFromEnvironment()

        processUserInput()

        //logger.info('processUserInput completed')
        def stageInputs = getStageInputs()
        this.script.node(this.nodeLabelExpr) {
            evaluateFromEnvironment()
            this.script.ws(createWorkspaceName()) {
                this.utils.withSshAgent( {
                    setupStages(stageInputs[0])
                }, this.checkoutCredentialsId)
            }
        }
        return ioMap
    }

    Map run() {
        Map ioMapModified

        this.script.stage("Run") {
            initializeFromEnvironment()

            processUserInput()

            this.script.timeout(time: this.pipelineTimeout, unit: 'MINUTES') {
                this.script.timestamps {
                    ioMapModified = runInternal()
                }
            }
        }

        return ioMapModified
    }

    
    /**
     * Internal functionality for running a pipeline by invoking the stages with appropriate data
     *
     * @return A Map of the input-output parameters passed to and modified by this pipeline
     */
    Map runInternal() {
        // Map that holds job name as key and job closure
        Map joblist = [:]
        //List for parallels for this pipeline
        def parallelList = []

        def stageInputs = getStageInputs()

        prePipeline(stageInputs[0])

        stageInputs.eachWithIndex {
            stageInput, listIndex ->
                String parallelName = generateJobName(stageInput)
                // Inject the parallelName to the stageInput for the job for backward reference
                stageInput["parallelName"] = parallelName
                parallelList.add(parallelName)
                String alternativeLabel = null
                if (altTargets.contains(parallelName)) {
                    //logger.info("Using alternative node label: ${altNodeLabelExpr} for ${parallelName}")
                    alternativeLabel = altNodeLabelExpr
                }

                initValueMap(parallelName)

                joblist[parallelName] = {
                    try {
                        if (this.nodeLabelExpr?.trim()) {
                            this.setupNodeWithStages(stageInput, alternativeLabel)
                        } else {
                            this.script.node {
                                evaluateFromEnvironment()
                                //NOTE: execution must always be encased in node, even if label is not specified!
                                this.setupStages(stageInput)
                            }
                        }

                        setMapValue(parallelName, "result", "SUCCESS")

                        //NOTE: Currently it's not possible to detect UNSTABLE state from a parallel run instance
                    } catch (Exception ex) {
                        setMapValue(parallelName, "result", "FAILURE")

                        throw ex
                    }
                }
        }

        try {
            this.script.parallel joblist
        }
        finally {
            Map results = [:]
            parallelList.each { item ->
                results[item] = getMapValue(item, "result")
            }
            this.postPipeline(results)
        }

        return ioMap
    }

    /**
     * Check the given combinations is valid or not
     */
    Boolean isValidCombinations(List combinations, List stageInputs) {
        if (combinations == null || combinations.isEmpty()) {
            return false
        } else {
            for (e in combinations) {
                if (!stageInputs.contains(e)) {
                    return false
                }
            }
            return true
        }
    }

    List getStageInputs() {
        // Generate inputs for stages and create a map of jobs
        def stageInputs = generateStageInputs()
        if (isValidCombinations(this.combinations, stageInputs)) {
            return this.combinations
        } else {
            return stageInputs
        }
    }

    /**
     * Function to get simplified parallel key string
     *
     * @return A String with parallel key name
     */
    String parallelKeyName() {
        //Subtract "s" from the parallel key if one exists
        String parallelKey = this.parallel[0]
        if (parallelKey.endsWith("s"))
            parallelKey = parallelKey[0..-2]  //NOTE: -2 actually removes just one char!

        return parallelKey
    }

    /**
     * Generates a job name based on the parallel keys and values.
     * Eg for parallel field "targets":
     * Legacy: "target: qemu-x86-64_nogfx:core-image-minimal"
     * New: "qemu-x86-64_nogfx:core-image-minimal"
     *
     * @param A Map with the inputs for stages
     * @return A String with job name
     */
    String generateJobName(Map stageInput) {
        // If no parallel keys exist, return a default name
        def jobName = "default"
        if (!this.parallel.isEmpty()) {
            //Legacy with prefix "target: "
            //jobName = parallelKeyName() + ": " + stageInput[parallelKeyName()]

            //New without prefix, same as key used in input-output map
            jobName = stageInput[parallelKeyName()]
        }

        return jobName
    }


    /**
     * Generates list of maps based on parallel keys, and processed user inputs
     * Each entry is an input for one parallelized instance of this pipeline
     *
     * @return A list of maps to be parallelled in separated stages
     */
    List generateStageInputs() {
        List stageInputs = []

        if (parallel.isEmpty()) {
            return stageInputs << this.inputs
        }

        // Map containing only non-parallel key-value pairs
        def linearInputKeys = this.inputs.keySet() - parallel
        def linearInputMap = this.inputs.subMap(linearInputKeys)
        if (this.parallel.size() > 1)
            throw new Exception("Only one parallel key is supported")

        String parallelKey = parallelKeyName()

        def parallelInputMap = this.inputs.subMap(parallel)

        List parallelKeyValues = []

        parallelInputMap.each { k, v ->
            v[0].tokenize(" ").each { splitValue ->
                parallelKeyValues.add(splitValue)
            }
        }

        stageInputs = linearParallelComb(parallelKey, parallelKeyValues)
        return stageInputs
    }

    /**
     * To combine parallel key value pairs with linear inputs
     *
     * @param A String containing parallel key to be used as field name
     * @param A List containing parallel key values
     * @return A List containing maps for each parallel instance
     */
    List linearParallelComb(String parallelKey, List parallelKeyValues) {
        List stageInputs = []

        // Map containing only non-parallel key-value pairs
        def linearInputKeys = this.inputs.keySet() - parallel
        def linearInputMap = this.inputs.subMap(linearInputKeys)
        if (this.parallel.size() > 1)
            throw new Exception("Only one parallel key is supported")

        // Generate stage input map list with added field for parallel key
        stageInputs = parallelKeyValues.collect {
            def stageMapInput = [:]
            stageMapInput[parallelKey] = it
            stageMapInput + linearInputMap
        }

        return stageInputs
    }

    /**
     * Function to reassemble regular expression mangled by input parser
     */
    String reassembleExpression(String input) {
        String str = ""
        input.tokenize("[],").each { item ->
            if (item == " ") {
                str += " || "
            }
            else {
                str += " " + item.trim()
            }
        }
        return str.trim()
    }

    /**
    * Support command to remove quotes (single/double) from a string
    *
    * @param A String to be sanitized
    */
    protected String removeQuotes(String str) {
        String sanitized = str.replaceAll('"', '')
        sanitized = sanitized.replaceAll("'", "")
        return sanitized.trim()
    }
}