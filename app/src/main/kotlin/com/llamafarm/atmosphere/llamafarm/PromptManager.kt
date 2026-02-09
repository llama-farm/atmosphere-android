package com.llamafarm.atmosphere.llamafarm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple prompt template system inspired by LlamaFarm server.
 * 
 * Manages prompt templates with {{system}} and {{user}} placeholders,
 * attaches templates to models, and formats chat messages.
 */
class PromptManager {
    
    /**
     * A prompt template with placeholders.
     */
    data class PromptTemplate(
        val name: String,
        val template: String,
        val description: String = ""
    ) {
        /**
         * Render the template with provided variables.
         */
        fun render(variables: Map<String, String>): String {
            var result = template
            variables.forEach { (key, value) ->
                result = result.replace("{{$key}}", value)
            }
            return result
        }
        
        /**
         * Render for chat completion (system + user).
         */
        fun renderChat(system: String?, user: String): String {
            val vars = mutableMapOf("user" to user)
            if (system != null) {
                vars["system"] = system
            }
            return render(vars)
        }
    }
    
    companion object {
        /**
         * Default templates for common use cases.
         */
        val DEFAULT_TEMPLATES = listOf(
            PromptTemplate(
                name = "default",
                template = """{{system}}

{{user}}""",
                description = "Simple system + user prompt"
            ),
            PromptTemplate(
                name = "assistant",
                template = """You are a helpful AI assistant.

{{user}}""",
                description = "Helpful assistant (no custom system)"
            ),
            PromptTemplate(
                name = "rag",
                template = """You are a helpful AI assistant. Answer the user's question based on the context provided.

# Context
{{context}}

# Question
{{user}}""",
                description = "RAG-enabled prompt with context injection"
            ),
            PromptTemplate(
                name = "vision",
                template = """You are a vision AI assistant. Analyze the image and respond to the user's request.

Image description: {{vision_context}}

User request: {{user}}""",
                description = "Vision-enabled prompt with image context"
            ),
            PromptTemplate(
                name = "code",
                template = """You are an expert programmer and code assistant.

{{user}}

Respond with clear, well-commented code.""",
                description = "Code generation assistant"
            ),
            PromptTemplate(
                name = "chat",
                template = """{{system}}

# Conversation History
{{history}}

# Current Message
User: {{user}}
Assistant:""",
                description = "Multi-turn conversation with history"
            )
        )
        
        /**
         * Persona-based system prompts.
         */
        val PERSONAS = mapOf(
            "assistant" to "You are a helpful, harmless, and honest AI assistant.",
            "expert" to "You are an expert in your field. Provide detailed, accurate, and well-researched answers.",
            "concise" to "You are a concise assistant. Answer questions briefly and directly.",
            "creative" to "You are a creative assistant. Think outside the box and provide imaginative responses.",
            "analytical" to "You are an analytical assistant. Break down problems systematically and provide logical reasoning.",
            "friendly" to "You are a friendly and casual assistant. Use a warm, conversational tone.",
            "professional" to "You are a professional assistant. Maintain a formal, business-appropriate tone."
        )
    }
    
    // Registry of templates
    private val templates = mutableMapOf<String, PromptTemplate>()
    
    // Model -> template associations
    private val modelTemplates = mutableMapOf<String, String>()
    
    init {
        // Register default templates
        DEFAULT_TEMPLATES.forEach { registerTemplate(it) }
    }
    
    /**
     * Register a new prompt template.
     */
    fun registerTemplate(template: PromptTemplate) {
        templates[template.name] = template
    }
    
    /**
     * Get a template by name.
     */
    fun getTemplate(name: String): PromptTemplate? = templates[name]
    
    /**
     * Get all registered templates.
     */
    fun getAllTemplates(): List<PromptTemplate> = templates.values.toList()
    
    /**
     * Associate a template with a model.
     */
    fun setModelTemplate(modelId: String, templateName: String) {
        if (!templates.containsKey(templateName)) {
            throw IllegalArgumentException("Template not found: $templateName")
        }
        modelTemplates[modelId] = templateName
    }
    
    /**
     * Get the template associated with a model (or default).
     */
    fun getModelTemplate(modelId: String): PromptTemplate {
        val templateName = modelTemplates[modelId] ?: "default"
        return templates[templateName] ?: templates["default"]!!
    }
    
    /**
     * Format a chat message using the model's template.
     */
    fun formatChat(
        modelId: String,
        user: String,
        system: String? = null,
        context: String? = null,
        history: String? = null
    ): String {
        val template = getModelTemplate(modelId)
        val vars = mutableMapOf("user" to user)
        
        if (system != null) vars["system"] = system
        if (context != null) vars["context"] = context
        if (history != null) vars["history"] = history
        
        return template.render(vars)
    }
    
    /**
     * Format chat for RAG (with context injection).
     */
    fun formatRagChat(
        modelId: String,
        user: String,
        context: String,
        system: String? = null
    ): String {
        val ragTemplate = templates["rag"]!!
        val vars = mutableMapOf(
            "user" to user,
            "context" to context
        )
        if (system != null) vars["system"] = system
        
        return ragTemplate.render(vars)
    }
    
    /**
     * Format chat for vision (with image context).
     */
    fun formatVisionChat(
        modelId: String,
        user: String,
        visionContext: String,
        system: String? = null
    ): String {
        val visionTemplate = templates["vision"]!!
        val vars = mutableMapOf(
            "user" to user,
            "vision_context" to visionContext
        )
        if (system != null) vars["system"] = system
        
        return visionTemplate.render(vars)
    }
    
    /**
     * Format multi-turn conversation.
     */
    fun formatConversation(
        modelId: String,
        user: String,
        history: List<Pair<String, String>>, // (role, content) pairs
        system: String? = null
    ): String {
        val chatTemplate = templates["chat"]!!
        
        // Format history
        val historyStr = history.joinToString("\n") { (role, content) ->
            "${role.capitalize()}: $content"
        }
        
        val vars = mutableMapOf(
            "user" to user,
            "history" to historyStr,
            "system" to (system ?: PERSONAS["assistant"]!!)
        )
        
        return chatTemplate.render(vars)
    }
    
    /**
     * Get a persona system prompt.
     */
    fun getPersona(name: String): String? = PERSONAS[name]
    
    /**
     * Get all available personas.
     */
    fun getAllPersonas(): Map<String, String> = PERSONAS.toMap()
    
    /**
     * Export templates to JSON for serialization.
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        
        // Templates
        val templatesArray = JSONArray()
        templates.values.forEach { template ->
            templatesArray.put(JSONObject().apply {
                put("name", template.name)
                put("template", template.template)
                put("description", template.description)
            })
        }
        json.put("templates", templatesArray)
        
        // Model associations
        val modelTemplatesObj = JSONObject()
        modelTemplates.forEach { (modelId, templateName) ->
            modelTemplatesObj.put(modelId, templateName)
        }
        json.put("model_templates", modelTemplatesObj)
        
        return json
    }
    
    /**
     * Import templates from JSON.
     */
    fun fromJson(json: JSONObject) {
        // Import templates
        val templatesArray = json.optJSONArray("templates")
        if (templatesArray != null) {
            for (i in 0 until templatesArray.length()) {
                val templateObj = templatesArray.getJSONObject(i)
                val template = PromptTemplate(
                    name = templateObj.getString("name"),
                    template = templateObj.getString("template"),
                    description = templateObj.optString("description", "")
                )
                registerTemplate(template)
            }
        }
        
        // Import model associations
        val modelTemplatesObj = json.optJSONObject("model_templates")
        if (modelTemplatesObj != null) {
            modelTemplatesObj.keys().forEach { modelId ->
                val templateName = modelTemplatesObj.getString(modelId)
                if (templates.containsKey(templateName)) {
                    modelTemplates[modelId] = templateName
                }
            }
        }
    }
}
