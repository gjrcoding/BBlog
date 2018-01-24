package iandroid.club.bblogs;

import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.logger.Logger;
import com.zchu.rxcache.data.CacheResult;
import com.zchu.rxcache.data.ResultFrom;
import com.zchu.rxcache.stategy.CacheStrategy;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import butterknife.BindView;
import butterknife.ButterKnife;
import iandroid.club.bblogs.entity.Article;
import iandroid.club.bblogs.entity.Blog;
import iandroid.club.bblogs.entity.Category;
import iandroid.club.bblogs.util.RxUtils;
import ren.yale.android.cachewebviewlib.WebViewCache;
import ren.yale.android.cachewebviewlib.utils.MD5Utils;
import rx.Observer;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;


/**
 * @Description: 博客爬取
 * @Author: 2tman
 * @Time: 2018/1/24
 */
public class CrawlerActivity extends AppCompatActivity {

    private String blogid = "";
    private String baseUrl = "";
    private Blog blog;

    private String targetUrl = "";

    private RecyclerView mRecyclerView;
    private List<Article> articleList = new ArrayList<>();
    private RecyclerView.Adapter adapter;

    @BindView(R.id.mProgressBar)
    ProgressBar mProgressBar;

    public void crawlerLoad() {
        mProgressBar.setVisibility(View.VISIBLE);

        RxUtils.createObservable(new Callable<List<Article>>() {
            @Override
            public List<Article> call() throws Exception {
                return jsoupGet();
            }
        }).subscribeOn(Schedulers.io())
                .compose(AppContext.getInstance().getRxCache()
                        .<List<Article>>transformer(MD5Utils.getMD5(targetUrl),
                                new TypeToken<List<Article>>() {
                                }.getType(), CacheStrategy.cacheAndRemote()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<CacheResult<List<Article>>>() {
                    @Override
                    public void onCompleted() {
                        mProgressBar.setVisibility(View.GONE);
                    }

                    @Override
                    public void onError(Throwable e) {
                        mProgressBar.setVisibility(View.GONE);
                    }

                    @Override
                    public void onNext(CacheResult<List<Article>> cacheResult) {
                        if (cacheResult.getData().size() > 0) {
                            articleList.clear();
                            articleList.addAll(cacheResult.getData());
                            adapter.notifyDataSetChanged();
                        }

                    }
                });

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crawler);
        ButterKnife.bind(this);
        mProgressBar.setVisibility(View.GONE);
        mRecyclerView = (RecyclerView) findViewById(R.id.mRecyclerView);


        blog = (Blog) getIntent().getSerializableExtra("blog");
        baseUrl = blog.getBaseUrl();
        blogid = blog.getBlogid();

        initRecyclerView();

        targetUrl = baseUrl + "/" + blogid;

        crawlerLoad();
    }

    private void initRecyclerView() {
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        dividerItemDecoration.setDrawable(getResources().getDrawable(R.drawable.drawable_divider));
        mRecyclerView.addItemDecoration(dividerItemDecoration);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(adapter = new RecyclerView.Adapter() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

                MyViewHolder commonViewHolder = new MyViewHolder(
                        LayoutInflater.from(CrawlerActivity.this).inflate(R.layout.layout_item_article, parent, false));
                return commonViewHolder;
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ((MyViewHolder) holder).setData(position, articleList.get(position));
            }

            @Override
            public int getItemCount() {
                return articleList.size();
            }
        });
    }

    private class MyViewHolder extends RecyclerView.ViewHolder {

        TextView tv_title;
        TextView tv_desc;
        TextView tv_time;
        TextView tv_read_count;

        public MyViewHolder(View itemView) {
            super(itemView);
            tv_title = (TextView) itemView.findViewById(R.id.tv_title);
            tv_desc = (TextView) itemView.findViewById(R.id.tv_desc);
            tv_time = (TextView) itemView.findViewById(R.id.tv_time);
            tv_read_count = (TextView) itemView.findViewById(R.id.tv_read_count);
        }

        public void setData(int position, final Article article) {
            tv_title.setText(article.getArticleTitle());
            tv_desc.setText(article.getArticleDesc());
            tv_time.setText(article.getCreatedTime());
            tv_read_count.setText(article.getReadCount());
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    //跳转详情
                    Intent intent = new Intent(CrawlerActivity.this, WebViewActivity.class);
                    intent.putExtra("url", blog.getBaseUrl() + article.getDetailUrl());
                    startActivity(intent);
                }
            });
        }
    }

    private String getListParentElement() {
        if (blog.getCategory() == Category.CSDN_BLOG) {
            return "div.list_item";
        } else if (blog.getCategory() == Category.JIANSHU_BLOG) {
            return "li.have-img";
        }
        return "";
    }

    private List<Article> jsoupGet() {
        List<Article> list = new ArrayList<>();
        try {
            Connection connection = Jsoup.connect(targetUrl);
            // 修改http包中的header,伪装成浏览器进行抓取
            connection.header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:32.0) Gecko/    20100101 Firefox/32.0");
            Document doc = connection.get();
            Elements articleList = doc.select(getListParentElement());

            if (articleList != null && articleList.size() > 0) {
                for (Element articleItem : articleList) {
                    Article article = null;
                    if (blog.getCategory() == Category.CSDN_BLOG) {
                        article = Article.getCsdnBlog(articleItem);
                    } else if (blog.getCategory() == Category.JIANSHU_BLOG) {
                        article = Article.getJianshuBlog(articleItem);
                    }
                    list.add(article);

                }

            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }


}
