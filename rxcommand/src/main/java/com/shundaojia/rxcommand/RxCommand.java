package com.shundaojia.rxcommand;

import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.LinkedList;
import java.util.List;

import io.reactivex.Notification;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

/**
 * Created by listen on 2017/3/16.
 * A command is an Observable triggered in response to some action, typicallyUI-related.
 */
public class RxCommand<T> {

    public static <T> RxCommand<T> create(Function<Object, Observable<T>> function) {
        return new RxCommand<>(function);
    }

    public static <T> RxCommand<T> create(Observable<Boolean> enabled, Function<Object, Observable<T>> function) {
        return new RxCommand<>(enabled, function);
    }

    private final List<ConnectableObservable<T>> mActiveExecutionObservables;
    private final Subject<List<ConnectableObservable<T>>> mActiveExecutionSubject;
    private final Observable<Boolean> mImmediateEnabled;
    private final Function<Object, Observable<T>> mFunc;

    private final Observable<Observable<T>> mExecutionObservables;
    private final Observable<Boolean> mExecuting;
    private final Observable<Boolean> mEnabled;
    private final Observable<Throwable> mErrors;

    private volatile boolean mAllowsConcurrentExecution;

    /**
     * create a command that is conditionally enabled.
     *
     * @param enabledObservable An observable of BOOLs which indicate whether the command should
     *              be enabled. {@link #enabled()} will be based on the latest value sent
     *                 from this observable. Before any values are sent, {@link #enabled()} will
     *               default to YES. This argument may be null.
     * @param func  - A function which will map each input value (passed to {@link #execute(Object)})
     *                 to a observable of work. The returned observable will be multicasted
     *                 to a replay subject, sent on {@link #executionObservables()}, then
     *                 subscribed to synchronously. Neither the function nor the
     *                 returned observable may be null.
     */
    public RxCommand(@Nullable Observable<Boolean> enabledObservable, @NonNull Function<Object, Observable<T>> func) {
        mActiveExecutionObservables = new LinkedList<>();
        mActiveExecutionSubject = BehaviorSubject.create();
        mFunc = func;

        Observable<ConnectableObservable<T>> newActiveExecutionObservables = mActiveExecutionSubject
                        .flatMap(new Function<List<ConnectableObservable<T>>, ObservableSource<ConnectableObservable<T>>>() {
                            @Override
                            public ObservableSource<ConnectableObservable<T>> apply(List<ConnectableObservable<T>> observables) throws Exception {
                                if (observables.isEmpty()) {
                                    return Observable.empty();
                                } else {
                                    return Observable.fromIterable(observables);
                                }
                            }
                        });

        mExecutionObservables = newActiveExecutionObservables
                .map(new Function<ConnectableObservable<T>, Observable<T>>() {
                    @Override
                    public Observable<T> apply(ConnectableObservable<T> tObservable) throws Exception {
                        return tObservable.onErrorResumeNext(Observable.<T>empty());
                    }
                })
                .observeOn(AndroidSchedulers.mainThread());

        mErrors = newActiveExecutionObservables
                        .flatMap(new Function<Observable<T>, ObservableSource<Throwable>>() {
                            @Override
                            public ObservableSource<Throwable> apply(Observable<T> tObservable) throws Exception {
                                return tObservable.materialize()
                                        .filter(new Predicate<Notification<T>>() {
                                            @Override
                                            public boolean test(Notification<T> tNotification) throws Exception {
                                                return tNotification.isOnError();
                                            }
                                        })
                                        .map(new Function<Notification<T>, Throwable>() {
                                            @Override
                                            public Throwable apply(Notification<T> tNotification) throws Exception {
                                                return tNotification.getError();
                                            }
                                        });
                            }
                        }).observeOn(AndroidSchedulers.mainThread());



        Observable<Boolean> immediateExecuting = mActiveExecutionSubject
                .startWith(mActiveExecutionObservables)
                .map(new Function<List<ConnectableObservable<T>>, Boolean>() {
                    @Override
                    public Boolean apply(List<ConnectableObservable<T>> observables) throws Exception {
                        return observables.size() > 0;
                    }
                });

        mExecuting = immediateExecuting
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged();

        Observable<Boolean> moreExecutionsAllowed = immediateExecuting
                .map(new Function<Boolean, Boolean>() {
                    @Override
                    public Boolean apply(Boolean executing) throws Exception {
                        if (mAllowsConcurrentExecution) {
                            return true;
                        } else {
                            return !executing;
                        }
                    }
                });

        if (enabledObservable == null) {
            enabledObservable = Observable.just(true);
        } else {
            enabledObservable = enabledObservable.startWith(true).replay(1).autoConnect();
        }

        mImmediateEnabled = Observable.combineLatest(enabledObservable, moreExecutionsAllowed, new BiFunction<Boolean, Boolean, Boolean>() {
            @Override
            public Boolean apply(Boolean enabled, Boolean allowed) throws Exception {
                return enabled && allowed;
            }
        });

        mEnabled = Observable
                .concat(mImmediateEnabled.take(1),
                        mImmediateEnabled.skip(1).observeOn(AndroidSchedulers.mainThread()))
                .distinctUntilChanged()
                .replay(1)
                .autoConnect();
    }

    /**
     * Call {@link #RxCommand(Observable, Function)} with a null `enabledObservable`.
     * @param func
     */
    public RxCommand(Function<Object, Observable<T>> func) {
        this(null, func);
    }

    /**
     * see {@link #allowsConcurrentExecution()}
     * @param allows
     */
    public final void setAllowsConcurrentExecution(boolean allows) {
        mAllowsConcurrentExecution = allows;
    }

    /**
     * An observable of the observables returned by successful invocations of {@link #execute(Object)}
     * (i.e., while the receiver is {@link #enabled()}).
     *
     * Errors will be automatically caught upon the inner observables, and sent upon
     * {@link #errors()} instead. If you _want_ to receive inner errors, use {@link #execute(Object)} or
     * {@link Observable#materialize()}
     *
     * Only executions that begin _after_ subscription will be sent upon this
     * observable. All inner observables will arrive upon the main thread.
     */
    public Observable<Observable<T>> executionObservables() {
        return mExecutionObservables;
    }

    /**
     * An observable of whether this command is currently executing.
     *
     * This will send true whenever {@link #execute(Object)} is invoked and the created observable has
     * not yet terminated. Once all executions have terminated, {@link #executing()} will
     * send false.
     *
     * This observable will send its current value upon subscription, and then all
     * future values on the main thread.
     */
    public Observable<Boolean> executing() {
        return mExecuting;
    }


    /**
     * An observable of whether this command is able to execute.
     * This will send false if:
     *
     *  - The command was created with an `enabledObservable`, and false is sent upon that
     *   observable, or
     *  - {@link #allowsConcurrentExecution()} is false and the command has started executing.
     *
     * Once the above conditions are no longer met, the observable will send true.
     *
     * This observable will send its current value upon subscription, and then all
     * future values on the main thread.
     */
    public Observable<Boolean> enabled() {
        return mEnabled;
    }

    /**
     * Forwards any errors that occur within observables returned by {@link #execute(Object)}.
     *
     * When an error occurs on a observable returned from {@link #execute(Object)}, this observable will
     * send the associated {@link Throwable} value as a `next` event (since an `error` event
     * would terminate the stream).
     *
     * After subscription, this observable will send all future errors on the main
     * thread.
     */
    public Observable<Throwable> errors() {
        return mErrors;
    }

    /**
     * Whether the command allows multiple executions to proceed concurrently.
     *
     * The default value for this property is false.
     */
    public boolean allowsConcurrentExecution() {
        return mAllowsConcurrentExecution;
    }

    /**
     * switch to the latest observable of observables send by {@link #executionObservables()}
     * @return
     */
    public Observable<T> switchToLatest() {
        return Observable.switchOnNext(mExecutionObservables);
    }

    /**
     * If the receiver is enabled, this method will:
     *
     *  1. Invoke the `func` given at the time of creation.
     *  2. Multicast the returned observable.
     *  3. Send the multicasted observable on {@link #executionObservables()}.
     *  4. Subscribe (connect) to the original observable on the main thread.
     *
     * @param input The input value to pass to the receiver's `func`. This may be null.
     * @return the multicasted observable, after subscription. If the receiver is not
     * enabled, returns a observable that will send an error.
     */
    @MainThread
    public final Observable<T> execute(@Nullable Object input) {
        boolean enabled = mImmediateEnabled.blockingFirst();
        if (!enabled) {
            return Observable.error(new IllegalStateException("The command is disabled and cannot be executed"));
        }
        try {
            Observable<T> observable = mFunc.apply(input);
            if (observable == null) {
                throw new RuntimeException(String.format("null Observable returned from observable func for value %s", input));
            }
            final ConnectableObservable<T> connection = observable
                    .subscribeOn(AndroidSchedulers.mainThread())
                    .publish();
            addActiveExecutionObservable(connection);
            connection.subscribe(new Observer<T>() {
                @Override
                public void onSubscribe(Disposable d) {

                }

                @Override
                public void onNext(T value) {

                }

                @Override
                public void onError(Throwable e) {
                    removeActiveExecutionObservable(connection);
                }

                @Override
                public void onComplete() {
                    removeActiveExecutionObservable(connection);
                }
            });
            connection.connect();
            return connection;
        } catch (Exception e) {
            e.printStackTrace();
            return Observable.error(e);
        }
    }

    private void addActiveExecutionObservable(ConnectableObservable<T> observable) {
        mActiveExecutionObservables.add(observable);
        mActiveExecutionSubject.onNext(mActiveExecutionObservables);
    }

    private void removeActiveExecutionObservable(ConnectableObservable<T> observable) {
        mActiveExecutionObservables.remove(observable);
        mActiveExecutionSubject.onNext(mActiveExecutionObservables);
    }

}

