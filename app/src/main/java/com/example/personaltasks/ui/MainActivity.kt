package com.example.personaltasks.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import java.time.format.DateTimeFormatter
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personaltasks.R
import com.example.personaltasks.adapter.TaskRvAdapter
import com.example.personaltasks.controllers.TaskController
import com.example.personaltasks.databinding.ActivityMainBinding
import com.example.personaltasks.model.FirebaseTaskService
import com.example.personaltasks.model.Task
import com.example.personaltasks.ui.Extras.EXTRA_TASK
import com.example.personaltasks.ui.Extras.EXTRA_VIEW_MODE
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Calendar

class MainActivity : AppCompatActivity(), OnTaskClickListener {

    private var currentSearchText: String? = null
    private var currentStartDate: LocalDate? = null
    private var currentEndDate: LocalDate? = null

    private val taskController: TaskController by lazy {
        TaskController.getInstance(this)
    }

    private val firebaseService = FirebaseTaskService()

    private val binding: ActivityMainBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val tasks = mutableListOf<Task>()
    private val taskAdapter: TaskRvAdapter by lazy {
        TaskRvAdapter(tasks, this)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val createTaskArl = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val task = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data.getParcelableExtra(EXTRA_TASK, Task::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(EXTRA_TASK)
                }
                val position = data.getIntExtra("TASK_POSITION", -1)

                task?.let { receivedTask ->
                    lifecycleScope.launch {
                        val localId = withContext(Dispatchers.IO) {
                            taskController.createTask(receivedTask)
                        }
                        val taskWithLocalId = receivedTask.copy(id = localId.toInt())

                        firebaseService.saveTask(taskWithLocalId) { success, firebaseId ->
                            if (success && firebaseId != null) {
                                val updatedTask = taskWithLocalId.copy(firebaseId = firebaseId)

                                lifecycleScope.launch {
                                    taskController.updateTask(updatedTask)
                                    if (position >= 0) {
                                        updateTaskInList(updatedTask, position)
                                    } else {
                                        addTaskToList(updatedTask)
                                    }
                                }
                            } else {
                                Log.e("MainActivity", "Falha ao salvar no Firebase: $firebaseId")
                            }
                        }
                    }
                }

            }
            loadTasksFromDatabase()
            updateDashboard()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            startActivity(Intent(this, AuthenticationActivity::class.java))
            finish()
            return
        }
        setupUi()
        setupSearchBar()
        setupDashboard()
        loadTasksFromDatabase()
        updateDashboard()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupDashboard() {
        binding.dashboard.overdueTasksContainer.setOnClickListener {
            showOverdueTasks()
        }

        binding.dashboard.todayTasksContainer.setOnClickListener {
            showTodayTasks()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateDashboard() {
        lifecycleScope.launch {
            val activeCount = withContext(Dispatchers.IO) { taskController.getActiveTasksCount() }
            val completedCount = withContext(Dispatchers.IO) { taskController.getCompletedTasksCount() }
            val overdueCount = withContext(Dispatchers.IO) { taskController.getOverdueTasksCount() }
            val todayCount = withContext(Dispatchers.IO) { taskController.getTodayTasksCount() }
            val totalCount = activeCount + completedCount

            binding.dashboard.activeTasksCount.text = activeCount.toString()
            binding.dashboard.completedTasksCount.text = completedCount.toString()
            binding.dashboard.overdueTasksCount.text = overdueCount.toString()
            binding.dashboard.todayTasksCount.text = todayCount.toString()

            val progress = if (totalCount > 0) {
                (completedCount * 100) / totalCount
            } else {
                0
            }

            binding.dashboard.progressBar.progress = progress
            binding.dashboard.progressText.text = "Progresso: $progress% ($completedCount de $totalCount tarefas)"
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun showOverdueTasks() {
        lifecycleScope.launch {
            val overdueTasks = withContext(Dispatchers.IO) { taskController.getOverdueTasks() }
            if (overdueTasks.isNotEmpty()) {
                tasks.clear()
                tasks.addAll(overdueTasks)
                taskAdapter.notifyDataSetChanged()
                Toast.makeText(this@MainActivity, "Mostrando ${overdueTasks.size} tarefas atrasadas", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Nenhuma tarefa atrasada!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTodayTasks() {
        lifecycleScope.launch {
            val todayTasks = withContext(Dispatchers.IO) { taskController.getTodayTasks() }
            if (todayTasks.isNotEmpty()) {
                tasks.clear()
                tasks.addAll(todayTasks)
                taskAdapter.notifyDataSetChanged()
                Toast.makeText(this@MainActivity, "Mostrando ${todayTasks.size} tarefas de hoje", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Nenhuma tarefa para hoje!", Toast.LENGTH_SHORT).show()
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupSearchBar() {
        binding.searchBar.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchText = s?.toString()?.takeIf { it.isNotBlank() }
                performSearch()
            }
        })

        binding.searchBar.startDateEditText.setOnClickListener {
            showDatePicker { date ->
                currentStartDate = date
                binding.searchBar.startDateEditText.setText(formatDate(date))
                performSearch()
            }
        }

        binding.searchBar.endDateEditText.setOnClickListener {
            showDatePicker { date ->
                currentEndDate = date
                binding.searchBar.endDateEditText.setText(formatDate(date))
                performSearch()
            }
        }

        binding.searchBar.searchButton.setOnClickListener {
            performSearch()
        }

        binding.searchBar.clearFiltersButton.setOnClickListener {
            clearFilters()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showDatePicker(onDateSelected: (LocalDate) -> Unit) {
        val calendar = Calendar.getInstance()
        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                onDateSelected(selectedDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }

    private fun performSearch() {
        lifecycleScope.launch {
            val searchResults = withContext(Dispatchers.IO) {
                taskController.searchTasks(currentSearchText, currentStartDate, currentEndDate)
            }

            tasks.clear()
            tasks.addAll(searchResults)
            taskAdapter.notifyDataSetChanged()

            val resultCount = searchResults.size
            val message = when {
                hasActiveFilters() -> "Encontradas $resultCount tarefas"
                else -> "Mostrando todas as tarefas ($resultCount)"
            }

            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun clearFilters() {
        currentSearchText = null
        currentStartDate = null
        currentEndDate = null

        binding.searchBar.searchEditText.setText("")
        binding.searchBar.startDateEditText.setText("")
        binding.searchBar.endDateEditText.setText("")

        loadTasksFromDatabase()
    }

    private fun hasActiveFilters(): Boolean {
        return currentSearchText != null || currentStartDate != null || currentEndDate != null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun formatDate(date: LocalDate): String {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        return date.format(formatter)
    }

    private fun setupUi() {
        setSupportActionBar(binding.toolbar.toolbar)
        configureTaskList()
    }

    private fun configureTaskList() {
        with(binding.taskList) {
            adapter = taskAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.add_task_mi -> {
                createTaskArl.launch(Intent(this, TasksActivity::class.java))
                true
            }
            R.id.action_deleted_tasks -> {
                startActivity(Intent(this, DeletedTasksActivity::class.java))
                true
            }
            R.id.action_logout -> {
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, AuthenticationActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadTasksFromDatabase() {
        if (hasActiveFilters()) {
            performSearch()
        } else {
            lifecycleScope.launch {
                val databaseTasks = withContext(Dispatchers.IO) {
                    taskController.getAllTasks()
                }
                tasks.clear()
                tasks.addAll(databaseTasks)
                taskAdapter.notifyDataSetChanged()
            }
        }
        updateDashboard()
    }

    override fun onViewTask(position: Int) {
        Intent(this, TasksActivity::class.java).apply {
            putExtra(EXTRA_TASK, tasks[position])
            putExtra(EXTRA_VIEW_MODE, true)
            startActivity(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onEditTask(position: Int) {
        Intent(this, TasksActivity::class.java).apply {
            putExtra(EXTRA_TASK, tasks[position])
            putExtra("TASK_POSITION", position)
            createTaskArl.launch(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        loadTasksFromDatabase()
        updateDashboard()
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRemoveTask(position: Int) {
        val taskToRemove = tasks[position]

        lifecycleScope.launch {
            val updatedTask = taskToRemove.copy(isDeleted = true)
            withContext(Dispatchers.IO) {
                taskController.updateTask(updatedTask)
            }
            tasks.removeAt(position)
            runOnUiThread {
                taskAdapter.notifyItemRemoved(position)
                Toast.makeText(
                    this@MainActivity,
                    "${taskToRemove.title} movida para excluídas!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (!taskToRemove.firebaseId.isNullOrEmpty()) {
                firebaseService.deleteTask(taskToRemove.firebaseId) { success, error ->
                    if (!success) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Erro ao excluir no Firebase: ${error ?: "Desconhecido"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        updateDashboard()
    }

    private fun updateTaskInList(task: Task, position: Int) {
        tasks[position] = task
        runOnUiThread {
            taskAdapter.notifyItemChanged(position)
            Toast.makeText(this, "Tarefa atualizada!", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun addTaskToList(task: Task) {
        tasks.add(task)
        runOnUiThread {
            taskAdapter.notifyItemInserted(tasks.lastIndex)
            Toast.makeText(this, "Tarefa adicionada!", Toast.LENGTH_SHORT).show()
        }
        loadTasksFromDatabase()
    }

    companion object {
        const val EDIT_TASK_REQUEST_CODE = 1
    }
}
