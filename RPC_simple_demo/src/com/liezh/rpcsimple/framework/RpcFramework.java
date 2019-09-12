package com.liezh.rpcsimple.framework;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * RPC 最重要的思想是用动态代理，在生成代理类的时候把远程调用的逻辑添加进去，从而隐式地被客户端调用，而不用关注具体如何调用。
 *
 * 待解决问题
 * 1. 多线程的解决（线程池）
 * 2. 异步的问题，现在是同步的（主要是客户端）
 * 3. 序列化和反序列化问题
 * 4. 性能提升
 * 5. 网络异常处理（服务端关闭，操作超时等等）
 * 6. 自定义网路协议
 * 7. 长连接，滑动窗口等常见TCP实现
 */
public class RpcFramework {

    /**
     * 暴露服务
     * @param service 实现的服务
     * @param port
     * @throws IOException
     */
    public static void export(final Object service, int port) throws IOException {
        if (service == null) {
            throw new IllegalArgumentException("service instance is null!");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port!");
        }
        System.out.println("Export service " + service.getClass().getName() + "on port " + port);
        // 在port端口上启动RPC服务
        ServerSocket serverSocket = new ServerSocket(port);
        while (true) {
            final Socket socket = serverSocket.accept();
            // 每一个客户端进入就创建一个线程进行处理
            // TODO 应该用其他的实现方式，防止内存溢出
            Thread t = new Thread(() -> {
                try {
                    // 获取当前接入的客户端发来的输入
                    try (ObjectInputStream input = new ObjectInputStream(socket.getInputStream())) {

                        String methodName = input.readUTF();
                        // 获取输入对象参数的对象类型列表
                        Class<?>[] parameterTypes = (Class<?>[]) input.readObject();
                        // 获取输入对象参数的实例
                        Object[] args = (Object[]) input.readObject();
                        // 创建返回的输出流
                        try (ObjectOutputStream output = new ObjectOutputStream(socket.getOutputStream())) {

                            // 创建客户端调用的方法，使用反射把方法实例化
                            Method method = service.getClass().getMethod(methodName, parameterTypes);
                            try {
                                // 调用方法逻辑，并得到结果
                                Object result = method.invoke(service, args);
                                // 正常调用，把结果写入output
                                output.writeObject(result);
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                // 异常调用，返回错误信息
                                output.writeObject(e);
                            }
                        } catch (NoSuchMethodException e) {
                            e.printStackTrace();
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        e.printStackTrace();
                    } finally {
                        socket.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            t.start();
        }
    }

    /**
     * 服务调用
     * @param interfaceClass    接口类型
     * @param host              监听服务器地址
     * @param port              监听服务器端口
     * @param <T>               泛型
     * @return
     */
    public static <T> T refer(final Class<T> interfaceClass, final String host, final int port) {
        if (interfaceClass == null)
            throw new IllegalArgumentException("Interface class == null");
        if (! interfaceClass.isInterface())
            throw new IllegalArgumentException("The " + interfaceClass.getName() + " must be interface class!");
        if (host == null || host.length() == 0)
            throw new IllegalArgumentException("Host == null!");
        if (port <= 0 || port > 65535)
            throw new IllegalArgumentException("Invalid port " + port);
        System.out.println("Get remote service " + interfaceClass.getName() + " from server " + host + ":" + port);
        Class<?>[] clazzs = {interfaceClass};
        // 利用jdk的动态代理，在实现客户端接口时，把调用RPC服务端的逻辑添加
        return (T) Proxy.newProxyInstance(interfaceClass.getClassLoader(), clazzs, getInvocationHandler(host, port));
    }

    private static InvocationHandler getInvocationHandler(String host, int port) {
        return (proxy, method, args) -> {
            // 创建socket客户端
            try (Socket socket = new Socket(host, port)) {
                // 把传递进来的参数和方法，发送到对应的socket service
                try (ObjectOutputStream output = new ObjectOutputStream(socket.getOutputStream())) {

                    // 把调用的方法名作为请求body和参数发送到RPC服务端
                    output.writeUTF(method.getName());
                    output.writeObject(method.getParameterTypes());
                    output.writeObject(args);
                    // 获取RPC服务端的返回结果
                    try (ObjectInputStream input = new ObjectInputStream(socket.getInputStream())) {
                        Object result = input.readObject();
                        if (result instanceof Throwable) {
                            throw (Throwable) result;
                        }
                        return result;
                    }
                }
            }
        };
    }

}
