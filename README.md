# RxCommand
一个基于RxJava，不需要借助XML来实现MVVM架构的轮子。 

## 为什么不用MVP 

MVP用接口隔离了view和presenter，需要定义接口，比较繁琐。在使用dagger这类依赖注入框架时，也不够便利，需要定义presenter的基类，也必须至少定义view的接口。presenter和view以接口的形式相互依赖，表面上它们是分离的，presenter对view是弱拥有关系，实际上presenter拥有对view的控制权，它们交互过于频繁，导致一个流程的代码散落在它们的各个角落，给阅读和理解代码带来不便。

## 为什么不用DataBinding

DataBinding需要把代码写到XML中，一方面不优雅，另一方面代码更为分散，无论是编写还是阅读测试维护都比较困难，实在是不喜欢。 

`RxJava + MVP`是目前比较流行的技术选型，只需要借助这个不到两百行代码的轮子，便可以轻松实现`RxJava + MVVM`。项目同时附加了demo。

## ViewModel

粗略地说，ViewModel和Presenter的职责是一样的，它们区别在于和View的协作方式。Presenter拥有对View的控制权，指挥View根据它的状态渲染界面。ViewModel不拥有View的引用，View观察ViewModel中状态的变化来主动地刷新自己的界面。所以MVP是命令式的，而MVVM是响应式的。

## 特性 

* 基于RxJava，拥有RxJava的所有优点
* 分离关注点，便于有选择地处理任务执行的状态（执行中，错误，完成等等）
* 不需要定义接口
* ViewModel是个普通类
* 相关代码集中，便于阅读和维护

## 使用 

### 集成到项目

```gradle
repositories {
    maven {
        url "http://shundaojia.bintray.com/maven"
    }
}
``` 

```
dependencies {
    compile 'com.shundaojia.rxcommand:rxcommand:0.0.4'
}
```
