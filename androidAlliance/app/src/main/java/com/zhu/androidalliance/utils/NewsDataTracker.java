package com.zhu.androidalliance.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zhu.androidalliance.common.Commons;
import com.zhu.androidalliance.pojo.dataObject.NewsTracker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NewsDataTracker {
    // 配置常量
    private static final String BEHAVIOR_API_URL = Commons.BASE_HOST+"/news/track";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 5000;
    private static final String QUEUE_NAME = "behavior_queue_v2";
    private static final String BEHAVIOR_CACHE = "behavior_cache";
    private static final int MAX_BATCH_SIZE = 50; // 单次最多发送50条
    private static final Object LOCK = new Object(); // 并发锁

    // 使用ConcurrentHashMap存储新闻ID与Behavior的映射关系
    private static final Map<Integer, NewsTracker> behaviorMap = new ConcurrentHashMap<>();

    // 线程池优化
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static Context appContext;

    public static void initialize(Context context) {
        appContext = context.getApplicationContext();
        // 从本地缓存加载已有的Behavior数据
        loadBehaviorCache();
    }

    // 从SharedPreferences加载Behavior缓存
    private static void loadBehaviorCache() {
        if (appContext == null) return;

        SharedPreferences prefs = appContext.getSharedPreferences(BEHAVIOR_CACHE, Context.MODE_PRIVATE);
        String json = prefs.getString("behavior_map", "{}");

        Type type = new TypeToken<Map<Integer, NewsTracker>>(){}.getType();
        Map<Integer, NewsTracker> cachedMap = JsonUtil.fromJson(json, type);

        if (cachedMap != null) {
            behaviorMap.putAll(cachedMap);
        }
    }

    // 保存Behavior缓存到SharedPreferences
    private static void saveBehaviorCache() {
        if (appContext == null) return;

        Gson gson = new Gson();
        String json = gson.toJson(behaviorMap);

        SharedPreferences prefs = appContext.getSharedPreferences(BEHAVIOR_CACHE, Context.MODE_PRIVATE);
        prefs.edit().putString("behavior_map", json).apply();
    }

    // 获取特定新闻的Behavior对象，如果不存在则创建
    public static NewsTracker getBehavior(Integer newsId) {
        if (newsId == null) {
            throw new IllegalArgumentException("News ID cannot be null or empty");
        }

        return behaviorMap.computeIfAbsent(newsId, k -> {
            NewsTracker newsTracker = new NewsTracker();
            newsTracker.setNewsId(newsId);
            return newsTracker;
        });
    }

    public static void track(NewsTracker newsTracker) {
        if (appContext == null) {
            throw new IllegalStateException("Call BehaviorTracker.initialize() first!");
        }

        if (newsTracker == null || newsTracker.getNewsId() == null) return;

        // 更新内存中的Behavior数据
        behaviorMap.put(newsTracker.getNewsId(), newsTracker);
        // 保存到本地缓存
        saveBehaviorCache();

        executor.execute(() -> {
            // 1. 序列化行为数据
            String json = new Gson().toJson(newsTracker);

            // 2. 线程安全入队
            synchronized (LOCK) {
                SharedPreferences prefs = getSharedPrefs();
                Set<String> queue = new HashSet<>(prefs.getStringSet("items", Collections.emptySet()));
                queue.add(json);
                prefs.edit().putStringSet("items", queue).apply();
            }

            // 3. 条件触发发送
            if (isNetworkAvailable()) {
                attemptSend();
            }
        });
    }

    private static void attemptSend() {
        executor.execute(() -> {
            List<String> batch = null;
            // 1. 获取待发送批次（线程安全）
            synchronized (LOCK) {
                SharedPreferences prefs = getSharedPrefs();
                Set<String> queue = prefs.getStringSet("items", Collections.emptySet());

                if (queue.isEmpty()) return;

                // 分批处理（避免超大请求）
                batch = new ArrayList<>(queue);
                int sendSize = Math.min(batch.size(), MAX_BATCH_SIZE);
                batch = batch.subList(0, sendSize);
            }

            // 2. 发送及重试
            boolean success = false;
            int retries = 0;

            while (!success && retries < MAX_RETRIES) {
                try {
                    success = sendBatch(batch);
                    if (success) removeFromQueue(batch);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (!success) {
                    retries++;
                    try { Thread.sleep(RETRY_DELAY_MS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    private static boolean sendBatch(List<String> items) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (String item : items) {
                jsonArray.put(new JSONObject(item));
            }

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(BEHAVIOR_API_URL)
                    .post(RequestBody.create(
                            jsonArray.toString(),
                            MediaType.parse("application/json")
                    ))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void removeFromQueue(List<String> sentItems) {
        synchronized (LOCK) {
            SharedPreferences prefs = getSharedPrefs();
            Set<String> queue = new HashSet<>(prefs.getStringSet("items", Collections.emptySet()));
            queue.removeAll(new HashSet<>(sentItems));
            prefs.edit().putStringSet("items", queue).apply();
        }
    }

    private static SharedPreferences getSharedPrefs() {
        return appContext.getSharedPreferences(QUEUE_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }
}