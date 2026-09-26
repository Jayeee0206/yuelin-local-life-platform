Vue.component("footBar", {
  template: `
    <nav class="foot" aria-label="主要导航">
      <button type="button" class="foot-box" :class="{active: activeBtn === 1}" @click="toPage(1)">
        <span class="foot-view"><i class="el-icon-s-home"></i></span><span class="foot-text">首页</span>
      </button>
      <button type="button" class="foot-box" :class="{active: activeBtn === 2}" @click="toPage(2)">
        <span class="foot-view"><i class="el-icon-location-outline"></i></span><span class="foot-text">好店</span>
      </button>
      <button type="button" class="foot-box" aria-label="发布笔记" @click="toPage(0)">
        <span class="add-btn" aria-hidden="true">+</span>
      </button>
      <button type="button" class="foot-box" :class="{active: activeBtn === 3}" @click="toPage(3)">
        <span class="foot-view"><i class="el-icon-connection"></i></span><span class="foot-text">关注</span>
      </button>
      <button type="button" class="foot-box" :class="{active: activeBtn === 4}" @click="toPage(4)">
        <span class="foot-view"><i class="el-icon-user"></i></span><span class="foot-text">我的</span>
      </button>
    </nav>`,
  props: ['activeBtn'],
  methods: {
    toPage(i) {
      if (i === 1) location.href = "/";
      else if (i === 2) location.href = "/shop-list.html?type=1&name=" + encodeURIComponent("美食");
      else if (i === 0) location.href = "/blog-edit.html";
      else if (i === 3) location.href = "/info.html?tab=follow";
      else if (i === 4) location.href = "/info.html";
    }
  }
});
