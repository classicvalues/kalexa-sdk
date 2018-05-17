package com.hp.kalexa.core.handler

import com.hp.kalexa.core.annotation.*
import com.hp.kalexa.core.extension.cast
import com.hp.kalexa.core.handler.SpeechHandler.Companion.INTENT_CONTEXT
import com.hp.kalexa.core.intent.BuiltInIntent
import com.hp.kalexa.core.intent.IntentExecutor
import com.hp.kalexa.core.util.IntentUtil.defaultBuiltInResponse
import com.hp.kalexa.core.util.IntentUtil.defaultGreetings
import com.hp.kalexa.core.util.IntentUtil.helpIntent
import com.hp.kalexa.core.util.IntentUtil.unsupportedIntent
import com.hp.kalexa.core.util.Util.findAnnotatedClasses
import com.hp.kalexa.core.util.Util.getIntentPackage
import com.hp.kalexa.core.util.Util.loadIntentClassesFromPackage
import com.hp.kalexa.model.extension.attribute
import com.hp.kalexa.model.request.*
import com.hp.kalexa.model.response.AlexaResponse
import com.hp.kalexa.model.response.alexaResponse
import com.sun.xml.internal.txw2.IllegalAnnotationException
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.superclasses


open class DefaultSpeechHandler : SpeechHandler {

    private val intentExecutorClasses: List<KClass<out IntentExecutor>> by lazy { loadIntentExecutorClasses() }
    private val intentClasses: Map<List<String>, KClass<out IntentExecutor>> by lazy { mapClassesWithIntentAnnotation() }
    private val intentExecutorInstances = mutableMapOf<KClass<out IntentExecutor>, IntentExecutor>()

    override fun handleSessionStartedRequest(envelope: AlexaRequestEnvelope<SessionStartedRequest>) = AlexaResponse.emptyResponse()

    override fun handleLaunchRequest(envelope: AlexaRequestEnvelope<LaunchRequest>): AlexaResponse {
        println("=========================== LaunchRequest =========================")
        println("Looking for LaunchIntent intents in ${getIntentPackage()}")
        return lookupIntentExecutorFromAnnotation<LaunchIntent>(envelope) { result ->
            when (result) {
                is Result.Content -> result.intentExecutor.onLaunchIntent(envelope.request)
                is Result.None -> defaultGreetings()
                is Result.Error -> throw result.exception
            }
        }
    }

    override fun handleIntentRequest(envelope: AlexaRequestEnvelope<IntentRequest>): AlexaResponse {
        println("=========================== IntentRequest =========================")
        val intentName = envelope.session.attribute<String>(INTENT_CONTEXT) ?: envelope.request.intent.name
        val builtInIntent = BuiltInIntent.getBuiltInIntent(envelope.request.intent.name)
        println("Intent name: $intentName - Built in Intent: $builtInIntent")
        return when {
            builtInIntent == null -> customIntent(intentName, envelope)
            intentName == BuiltInIntent.FALLBACK_INTENT.rawValue -> fallbackIntent(envelope)
            intentName == BuiltInIntent.HELP_INTENT.rawValue -> helpIntent(envelope)
            intentName == builtInIntent.rawValue -> unknownIntentContext(builtInIntent, envelope)
            else -> builtInIntent(intentName, builtInIntent, envelope)
        }
    }

    private fun customIntent(intentName: String, envelope: AlexaRequestEnvelope<IntentRequest>): AlexaResponse {
        val intentExecutor = getIntentExecutorOf(intentName, envelope)
        return intentExecutor?.let { executor ->
            val alexaResponse = executor.onIntentRequest(envelope.request)
            generateResponse(executor, alexaResponse)
        } ?: unknownIntentException(intentName)
    }

    private fun fallbackIntent(envelope: AlexaRequestEnvelope<IntentRequest>): AlexaResponse {
        return lookupIntentExecutorFromAnnotation<FallbackIntent>(envelope) { result ->
            when (result) {
                is Result.Content -> result.intentExecutor.onFallbackIntent(envelope.request)
                is Result.None -> unsupportedIntent()
                is Result.Error -> throw result.exception
            }
        }
    }

    private fun helpIntent(envelope: AlexaRequestEnvelope<IntentRequest>): AlexaResponse {
        return lookupIntentExecutorFromAnnotation<HelpIntent>(envelope) { result ->
            when (result) {
                is Result.Content -> result.intentExecutor.onHelpIntent(envelope.request)
                is Result.None -> helpIntent()
                is Result.Error -> throw result.exception
            }
        }
    }

    private fun unknownIntentContext(builtInIntent: BuiltInIntent, envelope: AlexaRequestEnvelope<IntentRequest>): AlexaResponse {
        return lookupIntentExecutorFromAnnotation<RecoverIntentContext>(envelope) { result ->
            when (result) {
                is Result.Content -> result.intentExecutor.onUnknownIntentContext(builtInIntent)
                is Result.None -> defaultBuiltInResponse(builtInIntent)
                is Result.Error -> throw result.exception
            }
        }
    }

    private fun builtInIntent(intentName: String, builtInIntent: BuiltInIntent, envelope: AlexaRequestEnvelope<IntentRequest>): AlexaResponse {
        val intentExecutor = getIntentExecutorOf(intentName, envelope)
        return intentExecutor?.let { executor ->
            val alexaResponse = executor.onBuiltInIntent(builtInIntent, envelope.request)
            generateResponse(executor, alexaResponse)
        } ?: unknownIntentException(intentName)
    }

    override fun handleElementSelectedRequest(envelope: AlexaRequestEnvelope<ElementSelectedRequest>): AlexaResponse {
        println("=========================== ElementSelectedRequest =========================")
        val intentName = envelope.session.attribute<String>(INTENT_CONTEXT)
                ?: envelope.request.token.split("\\|").first()
        val intentExecutor = getIntentExecutorOf(intentName, envelope)
        return intentExecutor?.let {
            val alexaResponse = it.onElementSelected(envelope.request)
            generateResponse(it, alexaResponse)
        } ?: unknownIntentException(intentName)
    }

    override fun handleSessionEndedRequest(envelope: AlexaRequestEnvelope<SessionEndedRequest>): AlexaResponse {
        return if (envelope.request.error != null && envelope.request.reason != null) {
            alexaResponse {
                response {
                    shouldEndSession = true
                    speech {
                        envelope.request.error?.type?.name ?: ""
                    }
                    simpleCard {
                        title = envelope.request.reason?.name ?: ""
                        content = envelope.request.error?.message ?: ""
                    }
                }
            }
        } else {
            AlexaResponse.emptyResponse()
        }
    }

    override fun handleConnectionsResponseRequest(envelope: AlexaRequestEnvelope<ConnectionsResponseRequest>): AlexaResponse {
        println("=========================== Connections.Response =========================")
        val intent = envelope.request.token.split("\\|").first()
        val intentExecutor = getIntentExecutorOf(intent, envelope)
        return intentExecutor?.let {
            val alexaResponse = it.onConnectionsResponse(envelope.request)
            generateResponse(it, alexaResponse)
        } ?: unknownIntentException(intent)
    }

    override fun handleConnectionsRequest(envelope: AlexaRequestEnvelope<ConnectionsRequest>): AlexaResponse {
        println("=========================== ConnectionsRequest =========================")
        return lookupIntentExecutorFromAnnotation<FulfillerIntent>(envelope) { result ->
            when (result) {
                is Result.Content -> result.intentExecutor.onConnectionsRequest(envelope.request)
                is Result.None -> unsupportedIntent()
                is Result.Error -> throw result.exception
            }
        }
    }

    private inline fun <reified T : Annotation> lookupIntentExecutorFromAnnotation(envelope: AlexaRequestEnvelope<*>,
                                                                                   callback: (Result) -> AlexaResponse): AlexaResponse {
        val annotationName = T::class.simpleName!!
        val classes = findAnnotatedClasses(intentExecutorClasses, T::class)
        println("Detected ${classes.size} intent classes with $annotationName annotation.")
        return when {
            classes.isEmpty() -> callback(Result.None)
            classes.size > 1 -> callback(Result.Error(illegalAnnotationArgument(annotationName)))
            else -> {
                val kclazz = classes.first()
                println("Class with $annotationName annotation: ${kclazz.simpleName}")
                val intentExecutor = getIntentExecutorOf(kclazz, envelope) as IntentExecutor
                callback(Result.Content(intentExecutor))
            }
        }
    }

    private fun generateResponse(executor: IntentExecutor, alexaResponse: AlexaResponse): AlexaResponse {
        return if (executor.isIntentContextLocked() && alexaResponse.sessionAttributes[INTENT_CONTEXT] == null) {
            alexaResponse.copy(sessionAttributes = alexaResponse.sessionAttributes + Pair(INTENT_CONTEXT, executor::class.java.simpleName))
        } else {
            alexaResponse
        }
    }

    private fun unknownIntentException(intentName: String): AlexaResponse {
        throw IllegalArgumentException("It was not possible to map intent $intentName to a Class. " +
                "Please make sure that the Intent class is annotated with @Intent or check intent package location")
    }

    private fun illegalAnnotationArgument(annotation: String): IllegalAnnotationException {
        return IllegalAnnotationException("The skill can only have one @$annotation method.")
    }

    @Suppress("unchecked_cast")
    private fun loadIntentExecutorClasses(): List<KClass<out IntentExecutor>> {
        return loadIntentClassesFromPackage()
                .filter { it.superclasses.find { it.simpleName == IntentExecutor::class.java.simpleName } != null }
                .cast()
    }

    /**
     * Look @Intent annotation up
     * @return Map of objects with kClass as key and a list of intents that maps to the kClass as value.
     */
    private fun mapClassesWithIntentAnnotation(): Map<List<String>, KClass<out IntentExecutor>> {
        return findAnnotatedClasses(intentExecutorClasses, Intent::class)
                .map { kclass ->
                    val intent = kclass.findAnnotation<Intent>()!!
                    val intents = intent.mapsTo.map { it } + kclass.simpleName!!
                    intents to kclass
                }.toMap()
    }

    /**
     * Retrieves an instance of a given intentName, if no such instance exists, it will be created, put into the hash
     * and return it
     * @param intentName
     * @return an instance of the intentName
     */
    private fun getIntentExecutorOf(intentName: String, envelope: AlexaRequestEnvelope<*>): IntentExecutor? {
        return intentClasses.entries.find {
            it.key.contains(intentName)
        }?.let {
            getIntentExecutorOf(it.value, envelope)
        }
    }

    private fun getIntentExecutorOf(kclazz: KClass<out IntentExecutor>, envelope: AlexaRequestEnvelope<*>): IntentExecutor? {
        val intentExecutor: IntentExecutor = intentExecutorInstances.getOrPut(kclazz) { kclazz.createInstance() }
        intentExecutor.sessionAttributes = envelope.session.attributes
        intentExecutor.session = envelope.session
        intentExecutor.context = envelope.context
        intentExecutor.version = envelope.version
        return intentExecutor
    }

    // Sealed
    sealed class Result {
        object None : Result()
        data class Error(val exception: Exception) : Result()
        data class Content(val intentExecutor: IntentExecutor) : Result()
    }
}