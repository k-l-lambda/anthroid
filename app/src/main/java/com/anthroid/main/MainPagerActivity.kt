package com.anthroid.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.anthroid.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Main activity with ViewPager2 for swipe navigation between Chat and Terminal.
 * Default page is Chat (index 0), swipe right for Terminal (index 1).
 */
class MainPagerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_pager)

        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)

        // Setup ViewPager2 adapter
        viewPager.adapter = MainPagerAdapter(this)

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Chat"
                1 -> "Terminal"
                else -> ""
            }
            tab.setIcon(when (position) {
                0 -> R.drawable.ic_claude
                1 -> R.drawable.ic_service_notification
                else -> 0
            })
        }.attach()

        // Start on Chat page (index 0)
        viewPager.setCurrentItem(0, false)
    }

    /**
     * ViewPager2 adapter for Chat and Terminal fragments.
     */
    private inner class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ClaudeFragment.newInstance()
                1 -> TerminalFragment.newInstance()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }

    override fun onBackPressed() {
        // If on Terminal page, go back to Chat
        if (viewPager.currentItem == 1) {
            viewPager.setCurrentItem(0, true)
        } else {
            super.onBackPressed()
        }
    }
}
