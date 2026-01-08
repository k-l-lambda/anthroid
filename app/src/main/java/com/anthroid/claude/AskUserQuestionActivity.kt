package com.anthroid.claude

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.anthroid.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Activity for displaying Claude's AskUserQuestion tool UI.
 * Shows questions with multiple-choice options and collects user responses.
 */
class AskUserQuestionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AskUserQuestionActivity"
        const val EXTRA_QUESTIONS_JSON = "questions_json"
        const val EXTRA_TOOL_ID = "tool_id"
        const val EXTRA_ANSWERS_JSON = "answers_json"
    }

    private lateinit var questionsContainer: LinearLayout
    private lateinit var submitButton: Button
    private lateinit var cancelButton: Button

    private val questions = mutableListOf<QuestionData>()
    private val answers = mutableMapOf<String, String>()

    data class QuestionData(
        val question: String,
        val header: String,
        val options: List<OptionData>,
        val multiSelect: Boolean
    )

    data class OptionData(
        val label: String,
        val description: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ask_user_question)

        supportActionBar?.apply {
            title = "Claude has a question"
            setDisplayHomeAsUpEnabled(true)
        }

        questionsContainer = findViewById(R.id.questions_container)
        submitButton = findViewById(R.id.btn_submit)
        cancelButton = findViewById(R.id.btn_cancel)

        // Parse questions from intent
        val questionsJson = intent.getStringExtra(EXTRA_QUESTIONS_JSON) ?: "[]"
        parseQuestions(questionsJson)

        // Build UI for each question
        buildQuestionsUI()

        submitButton.setOnClickListener { submitAnswers() }
        cancelButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun parseQuestions(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val qObj = jsonArray.getJSONObject(i)
                val optionsArray = qObj.getJSONArray("options")
                val options = mutableListOf<OptionData>()
                for (j in 0 until optionsArray.length()) {
                    val optObj = optionsArray.getJSONObject(j)
                    options.add(OptionData(
                        label = optObj.getString("label"),
                        description = optObj.optString("description", "")
                    ))
                }
                questions.add(QuestionData(
                    question = qObj.getString("question"),
                    header = qObj.optString("header", ""),
                    options = options,
                    multiSelect = qObj.optBoolean("multiSelect", false)
                ))
            }
            Log.i(TAG, "Parsed ${questions.size} questions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse questions JSON", e)
        }
    }

    private fun buildQuestionsUI() {
        questionsContainer.removeAllViews()

        questions.forEachIndexed { index, question ->
            val questionView = layoutInflater.inflate(R.layout.item_question, questionsContainer, false)

            // Header chip
            val headerChip = questionView.findViewById<TextView>(R.id.question_header)
            if (question.header.isNotEmpty()) {
                headerChip.text = question.header
                headerChip.visibility = View.VISIBLE
            } else {
                headerChip.visibility = View.GONE
            }

            // Question text
            val questionText = questionView.findViewById<TextView>(R.id.question_text)
            questionText.text = question.question

            // Options container
            val optionsContainer = questionView.findViewById<LinearLayout>(R.id.options_container)

            // "Other" input
            val otherInput = questionView.findViewById<EditText>(R.id.other_input)
            val otherRadio = questionView.findViewById<RadioButton>(R.id.other_radio)
            val otherCheck = questionView.findViewById<CheckBox>(R.id.other_check)

            if (question.multiSelect) {
                // Use checkboxes for multi-select
                question.options.forEach { option ->
                    val checkBox = CheckBox(this).apply {
                        text = option.label
                        tag = option.label
                        setOnCheckedChangeListener { _, _ ->
                            updateAnswer(index, question, optionsContainer, otherInput, otherCheck)
                        }
                    }

                    // Add description if present
                    if (option.description.isNotEmpty()) {
                        val container = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, 8, 0, 8)
                        }
                        container.addView(checkBox)
                        val descView = TextView(this).apply {
                            text = option.description
                            setTextColor(resources.getColor(android.R.color.darker_gray, null))
                            textSize = 12f
                            setPadding(48, 0, 0, 0)
                        }
                        container.addView(descView)
                        optionsContainer.addView(container)
                    } else {
                        optionsContainer.addView(checkBox)
                    }
                }

                // Show "Other" checkbox
                otherCheck.visibility = View.VISIBLE
                otherRadio.visibility = View.GONE
                otherCheck.setOnCheckedChangeListener { _, isChecked ->
                    otherInput.visibility = if (isChecked) View.VISIBLE else View.GONE
                    updateAnswer(index, question, optionsContainer, otherInput, otherCheck)
                }
                otherInput.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        updateAnswer(index, question, optionsContainer, otherInput, otherCheck)
                    }
                })
            } else {
                // Use radio buttons for single select
                val radioGroup = RadioGroup(this).apply {
                    orientation = RadioGroup.VERTICAL
                }

                // Track all radio buttons for manual unchecking (needed for nested layouts)
                val allRadioButtons = mutableListOf<RadioButton>()

                question.options.forEachIndexed { optIndex, option ->
                    val radioButton = RadioButton(this).apply {
                        id = View.generateViewId()
                        text = option.label
                        tag = option.label
                    }
                    allRadioButtons.add(radioButton)

                    // Add click listener to handle nested RadioButtons
                    radioButton.setOnClickListener {
                        // Uncheck other radios
                        allRadioButtons.forEach { rb -> if (rb != radioButton) rb.isChecked = false }
                        otherRadio.isChecked = false
                        otherInput.visibility = View.GONE
                        // Update answer directly
                        answers[question.question] = option.label
                        Log.d(TAG, "Radio clicked: Q$index = ${option.label}")
                    }

                    if (option.description.isNotEmpty()) {
                        val container = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, 8, 0, 8)
                        }
                        container.addView(radioButton)
                        val descView = TextView(this).apply {
                            text = option.description
                            setTextColor(resources.getColor(android.R.color.darker_gray, null))
                            textSize = 12f
                            setPadding(48, 0, 0, 0)
                        }
                        container.addView(descView)
                        radioGroup.addView(container)
                    } else {
                        radioGroup.addView(radioButton)
                    }
                }

                // Add "Other" radio option
                otherRadio.id = View.generateViewId()
                otherRadio.visibility = View.VISIBLE
                otherCheck.visibility = View.GONE
                (otherRadio.parent as? ViewGroup)?.removeView(otherRadio)
                otherRadio.setOnClickListener {
                    // Uncheck all other radios
                    allRadioButtons.forEach { rb -> rb.isChecked = false }
                    otherRadio.isChecked = true
                    otherInput.visibility = View.VISIBLE
                    // Update answer
                    val otherText = otherInput.text.toString().ifBlank { "Other" }
                    answers[question.question] = otherText
                    Log.d(TAG, "Other clicked: Q$index = $otherText")
                }
                radioGroup.addView(otherRadio)

                radioGroup.setOnCheckedChangeListener { _, checkedId ->
                    val isOther = checkedId == otherRadio.id
                    otherInput.visibility = if (isOther) View.VISIBLE else View.GONE
                    updateAnswerRadio(index, question, radioGroup, otherInput, otherRadio.id)
                }

                otherInput.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        updateAnswerRadio(index, question, radioGroup, otherInput, otherRadio.id)
                    }
                })

                optionsContainer.addView(radioGroup)
            }

            questionsContainer.addView(questionView)
        }
    }

    private fun updateAnswer(
        index: Int,
        question: QuestionData,
        optionsContainer: LinearLayout,
        otherInput: EditText,
        otherCheck: CheckBox
    ) {
        val selectedLabels = mutableListOf<String>()

        // Collect checked options
        for (i in 0 until optionsContainer.childCount) {
            val child = optionsContainer.getChildAt(i)
            val checkBox = if (child is CheckBox) child
                           else (child as? LinearLayout)?.getChildAt(0) as? CheckBox
            if (checkBox?.isChecked == true) {
                selectedLabels.add(checkBox.tag as String)
            }
        }

        // Add "Other" if checked and has content
        if (otherCheck.isChecked && otherInput.text.isNotBlank()) {
            selectedLabels.add(otherInput.text.toString())
        }

        val answerKey = question.question
        answers[answerKey] = selectedLabels.joinToString(", ")
        Log.d(TAG, "Answer updated: Q${index} = ${answers[answerKey]}")
    }

    private fun updateAnswerRadio(
        index: Int,
        question: QuestionData,
        radioGroup: RadioGroup,
        otherInput: EditText,
        otherRadioId: Int
    ) {
        val checkedId = radioGroup.checkedRadioButtonId
        if (checkedId == -1) {
            answers.remove(question.question)
            return
        }

        val answer = if (checkedId == otherRadioId) {
            otherInput.text.toString().ifBlank { "Other" }
        } else {
            // Find the checked radio button
            val checkedView = radioGroup.findViewById<View>(checkedId)
            val radioButton = if (checkedView is RadioButton) checkedView
                              else (checkedView as? LinearLayout)?.getChildAt(0) as? RadioButton
            radioButton?.tag as? String ?: "Unknown"
        }

        answers[question.question] = answer
        Log.d(TAG, "Answer updated: Q${index} = $answer")
    }

    private fun submitAnswers() {
        // Validate all questions have answers
        val unanswered = questions.filter { q -> answers[q.question].isNullOrBlank() }
        if (unanswered.isNotEmpty()) {
            val msg = "Please answer all questions"
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Format answers as JSON
        val answersJson = JSONObject()
        answers.forEach { (question, answer) ->
            answersJson.put(question, answer)
        }

        Log.i(TAG, "Submitting answers: $answersJson")

        val resultIntent = Intent().apply {
            putExtra(EXTRA_ANSWERS_JSON, answersJson.toString())
            putExtra(EXTRA_TOOL_ID, intent.getStringExtra(EXTRA_TOOL_ID))
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(Activity.RESULT_CANCELED)
        finish()
        return true
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_CANCELED)
        super.onBackPressed()
    }
}
