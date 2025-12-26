package com.anthroid.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.anthroid.R
import com.anthroid.app.TermuxActivity

/**
 * Fragment for Terminal tab.
 * Shows a button to open full-screen terminal activity.
 */
class TerminalFragment : Fragment() {

    private var openTerminalButton: Button? = null
    private var statusText: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_terminal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.terminal_loading)
        openTerminalButton = view.findViewById(R.id.open_terminal_button)

        statusText?.text = "Terminal"

        openTerminalButton?.setOnClickListener {
            openTermuxActivity()
        }

        // Auto-open terminal when this fragment becomes visible
        openTermuxActivity()
    }

    private fun openTermuxActivity() {
        val intent = Intent(requireContext(), TermuxActivity::class.java)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // When user returns from terminal, they're back on this fragment
    }

    companion object {
        fun newInstance(): TerminalFragment = TerminalFragment()
    }
}
