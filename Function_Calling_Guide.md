# Implementing Your Custom Logic
To build specialized agents that go beyond our provided demos, you can fine-tune your own version of the model and customize the app with your functions to call.

## Clone the Repository
```shell
git clone git@github.com:google-ai-edge/gallery.git
```

This will create a local copy of the repository.

## Define Your Action Type 

In [Actions.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/Actions.kt), add a new entry to the `ActionType` enum and create a class that extends `Action` to define your specific function name, icon, and parameters.

```kotlin
enum class ActionType {
  // ... existing types
  ACTION_NEW_CUSTOM_FUNCTION,
}

class NewCustomAction(val param: String) : Action(
  type = ActionType.ACTION_NEW_CUSTOM_FUNCTION,
  icon = Icons.Outlined.Favorite, // Choose an appropriate icon
  functionCallDetails = FunctionCallDetails(
    functionName = "newCustomFunction",
    parameters = listOf(Pair("param", param))
  )
)
```

## Add Your Tool Definition

In [MobileActionsTools.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsTools.kt), create a new function annotated with `@Tool` and `@ToolParam`. This function should call the `onFunctionCalled` callback to pass the specific action to your app logic.

```kotlin
class MobileActionsTools(val onFunctionCalled: (Action) -> Unit): Toolset {
  // ... existing tools

  /** Description for the model. */
  @Tool(description = "Description of what this function does")
  fun newCustomFunction(
    @ToolParam(description = "Description of the parameter") param: String
  ): Map<String, String> {
    onFunctionCalled(NewCustomAction(param = param))
    return mapOf("result" to "success")
  }
}
```

## Implement Your Action Logic 

Update the `performAction` method in [MobileActionsViewModel.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsViewModel.kt) to handle your new action type. This is where you implement the actual Android logic, such as using the `CameraManager` or starting a new `Intent`.

```kotlin
fun performAction(action: Action, context: Context): String {
  return when (action) {
    // ... existing actions
    is NewCustomAction -> handleNewCustomAction(context, action.param)
    else -> ""
  }
}

private fun handleNewCustomAction(context: Context, param: String): String {
  // Implement your Android logic here (e.g., Toast, Intent, etc.)
  return ""
}
```

## Update the System Prompt (Optional) 

If your function requires specific context like the current time or device state, update the `getSystemPrompt()` function in [MobileActionsTask.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsTask.kt) to ensure the model has the information it needs.

## Build and Install 

Navigate to the `Android/src/` directory in your terminal and use the Gradle wrapper to build the debug version of the app and install it directly onto your connected device:

```shell
cd gallery/Android/src/
./gradlew installDebug
```

Gradle will take care of downloading dependencies, compiling the code, and deploying the APK. Once finished, you should see "Edge Gallery" appearing in your app drawer!
