/*
 * TaskChain for Minecraft Plugins
 *
 * Written by Aikar <aikar@aikar.co>
 * https://aikar.co
 * https://starlis.com
 *
 * @license MIT
 */

package co.aikar.taskchain;

import co.aikar.taskchain.TaskChainTasks.AsyncExecutingFirstTask;
import co.aikar.taskchain.TaskChainTasks.AsyncExecutingGenericTask;
import co.aikar.taskchain.TaskChainTasks.AsyncExecutingTask;
import co.aikar.taskchain.TaskChainTasks.FirstTask;
import co.aikar.taskchain.TaskChainTasks.GenericTask;
import co.aikar.taskchain.TaskChainTasks.LastTask;
import co.aikar.taskchain.TaskChainTasks.Task;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;


/**
 * TaskChain v3.0 - by Daniel Ennis <aikar@aikar.co>
 *
 * Facilitates Control Flow for a game scheduler to easily jump between
 * Async and Sync execution states without deeply nested callbacks,
 * passing the response of the previous task to the next task to use.
 *
 * Also can be used to guarantee execution order to 2 ensure
 * that 2 related actions can never run at same time, and 1 set of tasks
 * will not start executing until the previous set is finished.
 *
 *
 * Find latest updates at https://taskchain.emc.gs
 */
@SuppressWarnings({"unused", "FieldAccessedSynchronizedAndUnsynchronized"})
public class TaskChain <T> {
    private static final ThreadLocal<TaskChain<?>> currentChain = new ThreadLocal<>();

    private final boolean shared;
    private final GameInterface impl;
    private final TaskChainFactory factory;
    private final String sharedName;
    private final Map<String, Object> taskMap = new HashMap<>(0);
    private final ConcurrentLinkedQueue<TaskHolder<?,?>> chainQueue = new ConcurrentLinkedQueue<>();

    private boolean executed = false;
    private boolean async;
    boolean done = false;

    private Object previous;
    private TaskHolder<?, ?> currentHolder;
    private Consumer<Boolean> doneCallback;
    private BiConsumer<Exception, Task<?, ?>> errorHandler;

    /* ======================================================================================== */
    TaskChain(TaskChainFactory factory) {
        this(factory, false, null);
    }

    TaskChain(TaskChainFactory factory, boolean shared, String sharedName) {
        this.factory = factory;
        this.impl = factory.getImplementation();
        this.shared = shared;
        this.sharedName = sharedName;
    }
    /* ======================================================================================== */
    // <editor-fold desc="// Getters & Setters">

    void setDoneCallback(Consumer<Boolean> doneCallback) {
        this.doneCallback = doneCallback;
    }

    BiConsumer<Exception, Task<?, ?>> getErrorHandler() {
        return errorHandler;
    }

    void setErrorHandler(BiConsumer<Exception, Task<?, ?>> errorHandler) {
        this.errorHandler = errorHandler;
    }
    // </editor-fold>
    /* ======================================================================================== */
    //<editor-fold desc="// API Methods">
    /**
     * Call to abort execution of the chain. Should be called inside of an executing task.
     */
    public static void abort() {
        TaskChainUtil.sneakyThrows(new AbortChainException());
    }

    /**
     * Usable only inside of an executing Task.
     *
     * Gets the current chain that is executing this task. This method should only be called on the same thread
     * that is executing the task.
     *
     * In an AsyncExecutingTask, You must call this method BEFORE passing control to another thread.
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public static TaskChain<?> getCurrentChain() {
        return currentChain.get();
    }

    /* ======================================================================================== */

    /**
     * Checks if the chain has a value saved for the specified key.
     * @param key
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public boolean hasTaskData(String key) {
        return taskMap.containsKey(key);
    }

    /**
     * Retrieves a value relating to a specific key, saved by a previous task.
     *
     * @param key
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> R getTaskData(String key) {
        return (R) taskMap.get(key);
    }

    /**
     * Saves a value for this chain so that a task furthur up the chain can access it.
     *
     * Useful for passing multiple values to the next (or furthur) tasks.
     *
     * @param key
     * @param val
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> R setTaskData(String key, Object val) {
        return (R) taskMap.put(key, val);
    }

    /**
     * Removes a saved value on the chain.
     *
     * @param key
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> R removeTaskData(String key) {
        return (R) taskMap.remove(key);
    }

    /**
     * Takes the previous tasks return value, stores it to the specified key
     * as Task Data, and then forwards that value to the next task.
     *
     * @param key
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<T> storeAsData(String key) {
        return current((val) -> {
            setTaskData(key, val);
            return val;
        });
    }

    /**
     * Reads the specified key from Task Data, and passes it to the next task.
     *
     * Will need to pass expected type such as chain.<Foo>returnData("key")
     *
     * @param key
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> returnData(String key) {
        return currentFirst(() -> (R) getTaskData(key));
    }

    /**
     * Returns the chain itself to the next task.
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<TaskChain<?>> returnChain() {
        return currentFirst(() -> this);
    }

    /**
     * Checks if the previous task return was null.
     *
     * If not null, the previous task return will forward to the next task.
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<T> abortIfNull() {
        return current((obj) -> {
            if (obj == null) {
                abort();
                return null;
            }
            return obj;
        });
    }

    /**
     * Checks if the previous task return was null, and aborts if it was, optionally
     * sending a message to the player.
     *
     * If not null, the previous task return will forward to the next task.
     */
    @SuppressWarnings("WeakerAccess")
    public <A1> TaskChain<T> abortIfNull(TaskChainNullAction<A1, ?, ?> action, A1 arg1) {
        return abortIfNull(action, arg1, null, null);
    }

    /**
     * Checks if the previous task return was null, and aborts if it was, optionally
     * sending a message to the player.
     *
     * If not null, the previous task return will forward to the next task.
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <A1, A2> TaskChain<T> abortIfNull(TaskChainNullAction<A1, A2, ?> action, A1 arg1, A2 arg2) {
        return abortIfNull(action, arg1, arg2, null);
    }

    /**
     * Checks if the previous task return was null, and aborts if it was, optionally
     * sending a message to the player.
     *
     * If not null, the previous task return will forward to the next task.
     * @param object
     * @param msg
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <A1, A2, A3> TaskChain<T> abortIfNull(TaskChainNullAction<A1, A2, A3> action, A1 arg1, A2 arg2, A3 arg3) {
        return current((obj) -> {
            if (obj == null) {
                if (action != null) {
                    action.onNull(arg1, arg2, arg3);
                }
                abort();
                return null;
            }
            return obj;
        });
    }

    /**
     * IMPLEMENTATION SPECIFIC!!
     * Consult your application implementation to understand how long 1 unit is.
     *
     * For example, in Minecraft it is a tick, which is roughly 50 milliseconds, but not guaranteed.
     *
     * Adds a delay to the chain execution.
     *
     * @param gameUnits # of game units to delay before next task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<T> delay(final int gameUnits) {
        //noinspection CodeBlock2Expr
        return currentCallback((input, next) -> {
            impl.scheduleTask(gameUnits, () -> next.accept(input));
        });
    }

    /**
     * Adds a real time delay to the chain execution.
     * Chain will abort if the delay is interrupted.
     *
     * @param duration duration of the delay before next task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<T> delay(final int duration, TimeUnit unit) {
        //noinspection CodeBlock2Expr
        return currentCallback((input, next) -> {
            impl.scheduleTask(duration, unit, () -> next.accept(input));
        });
    }

    /**
     * Execute a task on the main thread, with no previous input, and a callback to return the response to.
     *
     * It's important you don't perform blocking operations in this method. Only use this if
     * the task will be scheduling a different sync operation outside of the TaskChains scope.
     *
     * Usually you could achieve the same design with a blocking API by switching to an async task
     * for the next task and running it there.
     *
     * This method would primarily be for cases where you need to use an API that ONLY provides
     * a callback style API.
     *
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> syncFirstCallback(AsyncExecutingFirstTask<R> task) {
        return add0(new TaskHolder<>(this, false, task));
    }

    /**
     * @see #syncFirstCallback(AsyncExecutingFirstTask) but ran off main thread
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> asyncFirstCallback(AsyncExecutingFirstTask<R> task) {
        return add0(new TaskHolder<>(this, true, task));
    }

    /**
     * @see #syncFirstCallback(AsyncExecutingFirstTask) but ran on current thread the Chain was created on
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> currentFirstCallback(AsyncExecutingFirstTask<R> task) {
        return add0(new TaskHolder<>(this, null, task));
    }

    /**
     * Execute a task on the main thread, with the last output, and a callback to return the response to.
     *
     * It's important you don't perform blocking operations in this method. Only use this if
     * the task will be scheduling a different sync operation outside of the TaskChains scope.
     *
     * Usually you could achieve the same design with a blocking API by switching to an async task
     * for the next task and running it there.
     *
     * This method would primarily be for cases where you need to use an API that ONLY provides
     * a callback style API.
     *
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> syncCallback(AsyncExecutingTask<R, T> task) {
        return add0(new TaskHolder<>(this, false, task));
    }

    /**
     * @see #syncCallback(AsyncExecutingTask), ran on main thread but no input or output
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> syncCallback(AsyncExecutingGenericTask task) {
        return add0(new TaskHolder<>(this, false, task));
    }

    /**
     * @see #syncCallback(AsyncExecutingTask) but ran off main thread
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> asyncCallback(AsyncExecutingTask<R, T> task) {
        return add0(new TaskHolder<>(this, true, task));
    }

    /**
     * @see #syncCallback(AsyncExecutingTask) but ran off main thread
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> asyncCallback(AsyncExecutingGenericTask task) {
        return add0(new TaskHolder<>(this, true, task));
    }

    /**
     * @see #syncCallback(AsyncExecutingTask) but ran on current thread the Chain was created on
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> currentCallback(AsyncExecutingTask<R, T> task) {
        return add0(new TaskHolder<>(this, null, task));
    }

    /**
     * @see #syncCallback(AsyncExecutingTask) but ran on current thread the Chain was created on
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> currentCallback(AsyncExecutingGenericTask task) {
        return add0(new TaskHolder<>(this, null, task));
    }

    /**
     * Execute task on main thread, with no input, returning an output
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> syncFirst(FirstTask<R> task) {
        return add0(new TaskHolder<>(this, false, task));
    }

    /**
     * @see #syncFirst(FirstTask) but ran off main thread
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> asyncFirst(FirstTask<R> task) {
        return add0(new TaskHolder<>(this, true, task));
    }

    /**
     * @see #syncFirst(FirstTask) but ran on current thread the Chain was created on
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> currentFirst(FirstTask<R> task) {
        return add0(new TaskHolder<>(this, null, task));
    }

    /**
     * Execute task on main thread, with the last returned input, returning an output
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> sync(Task<R, T> task) {
        return add0(new TaskHolder<>(this, false, task));
    }

    /**
     * Execute task on main thread, with no input or output
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> sync(GenericTask task) {
        return add0(new TaskHolder<>(this, false, task));
    }

    /**
     * @see #sync(Task) but ran off main thread
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> async(Task<R, T> task) {
        return add0(new TaskHolder<>(this, true, task));
    }

    /**
     * @see #sync(GenericTask) but ran off main thread
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> async(GenericTask task) {
        return add0(new TaskHolder<>(this, true, task));
    }

    /**
     * @see #sync(Task) but ran on current thread the Chain was created on
     * @param task
     * @param <R>
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public <R> TaskChain<R> current(Task<R, T> task) {
        return add0(new TaskHolder<>(this, null, task));
    }

    /**
     * @see #sync(GenericTask) but ran on current thread the Chain was created on
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> current(GenericTask task) {
        return add0(new TaskHolder<>(this, null, task));
    }


    /**
     * Execute task on main thread, with the last output, and no furthur output
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> syncLast(LastTask<T> task) {
        return add0(new TaskHolder<>(this, false, task));
    }

    /**
     * @see #syncLast(LastTask) but ran off main thread
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> asyncLast(LastTask<T> task) {
        return add0(new TaskHolder<>(this, true, task));
    }

    /**
     * @see #syncLast(LastTask) but ran on current thread the Chain was created on
     * @param task
     * @return
     */
    @SuppressWarnings("WeakerAccess")
    public TaskChain<?> currentLast(LastTask<T> task) {
        return add0(new TaskHolder<>(this, null, task));
    }

    /**
     * Finished adding tasks, begins executing them.
     */
    @SuppressWarnings("WeakerAccess")
    public void execute() {
        execute((Consumer<Boolean>) null, null);
    }

    /**
     * Finished adding tasks, begins executing them with a done notifier
     */
    public void execute(Runnable done) {
        execute((finished) -> done.run(), null);
    }

    /**
     * Finished adding tasks, begins executing them with a done notifier and error handler
     */
    public void execute(Runnable done, BiConsumer<Exception, Task<?, ?>> errorHandler) {
        execute((finished) -> done.run(), errorHandler);
    }

    /**
     * Finished adding tasks, with a done notifier
     * @param done
     */
    public void execute(Consumer<Boolean> done) {
        execute(done, null);
    }

    /**
     * Finished adding tasks, begins executing them, with an error handler
     * @param errorHandler
     */
    public void execute(BiConsumer<Exception, Task<?, ?>> errorHandler) {
        execute((Consumer<Boolean>) null, errorHandler);
    }

    /**
     * Finished adding tasks, begins executing them with a done notifier and error handler
     * @param done
     * @param errorHandler
     */
    public void execute(Consumer<Boolean> done, BiConsumer<Exception, Task<?, ?>> errorHandler) {
        this.doneCallback = done;
        this.errorHandler = errorHandler;
        execute0();
    }

    // </editor-fold>
    /* ======================================================================================== */
    //<editor-fold desc="// Implementation Details">
    void execute0() {
        synchronized (this) {
            if (this.executed) {
                if (this.shared) {
                    return;
                }
                throw new RuntimeException("Already executed");
            }
            this.executed = true;
        }
        async = !impl.isMainThread();
        nextTask();
    }

    protected void done(boolean finished) {
        this.done = true;
        if (this.shared) {
            factory.removeSharedChain(this.sharedName);
        }
        if (this.doneCallback != null) {
            this.doneCallback.accept(finished);
        }
    }

    @SuppressWarnings({"rawtypes", "WeakerAccess"})
    protected TaskChain add0(TaskHolder<?,?> task) {
        synchronized (this) {
            if (!this.shared && this.executed) {
                throw new RuntimeException("TaskChain is executing");
            }
        }

        this.chainQueue.add(task);
        return this;
    }

    /**
     * Fires off the next task, and switches between Async/Sync as necessary.
     */
    private void nextTask() {
        synchronized (this) {
            this.currentHolder = this.chainQueue.poll();
            if (this.currentHolder == null) {
                this.done = true; // to ensure its done while synchronized
            }
        }

        if (this.currentHolder == null) {
            this.previous = null;
            // All Done!
            this.done(true);
            return;
        }

        Boolean isNextAsync = this.currentHolder.async;
        if (isNextAsync == null || factory.shutdown) {
            this.currentHolder.run();
        } else if (isNextAsync) {
            if (this.async) {
                this.currentHolder.run();
            } else {
                impl.postAsync(() -> {
                    this.async = true;
                    this.currentHolder.run();
                });
            }
        } else {
            if (this.async) {
                impl.postToMain(() -> {
                    this.async = false;
                    this.currentHolder.run();
                });
            } else {
                this.currentHolder.run();
            }
        }
    }
    // </editor-fold>
    /* ======================================================================================== */
    //<editor-fold desc="// TaskHolder">
    /**
     * Provides foundation of a task with what the previous task type should return
     * to pass to this and what this task will return.
     * @param <R> Return Type
     * @param <A> Argument Type Expected
     */
    @SuppressWarnings("AccessingNonPublicFieldOfAnotherObject")
    private class TaskHolder<R, A> {
        private final TaskChain<?> chain;
        private final Task<R, A> task;
        public final Boolean async;

        private boolean executed = false;
        private boolean aborted = false;

        private TaskHolder(TaskChain<?> chain, Boolean async, Task<R, A> task) {
            this.task = task;
            this.chain = chain;
            this.async = async;
        }

        /**
         * Called internally by Task Chain to facilitate executing the task and then the next task.
         */
        private void run() {
            final Object arg = this.chain.previous;
            this.chain.previous = null;
            final R res;
            try {
                currentChain.set(this.chain);
                if (this.task instanceof AsyncExecutingTask) {
                    ((AsyncExecutingTask<R, A>) this.task).runAsync((A) arg, this::next);
                } else {
                    next(this.task.run((A) arg));
                }
            } catch (Exception e) {
                //noinspection ConstantConditions
                if (e instanceof AbortChainException) {
                    this.abort();
                    return;
                }
                if (this.chain.errorHandler != null) {
                    this.chain.errorHandler.accept(e, this.task);
                } else {
                    TaskChainUtil.logError("TaskChain Exception on " + this.task.getClass().getName());
                    e.printStackTrace();
                }
                this.abort();
            } finally {
                currentChain.remove();
            }
        }

        /**
         * Abort the chain, and clear tasks for GC.
         */
        private synchronized void abort() {
            this.aborted = true;
            this.chain.previous = null;
            this.chain.chainQueue.clear();
            this.chain.done(false);
        }

        /**
         * Accepts result of previous task and executes the next
         */
        private void next(Object resp) {
            synchronized (this) {
                if (this.aborted) {
                    this.chain.done(false);
                    return;
                }
                if (this.executed) {
                    this.chain.done(false);
                    throw new RuntimeException("This task has already been executed.");
                }
                this.executed = true;
            }

            this.chain.async = !TaskChain.this.impl.isMainThread(); // We don't know where the task called this from.
            this.chain.previous = resp;
            this.chain.nextTask();
        }
    }
    //</editor-fold>
}
