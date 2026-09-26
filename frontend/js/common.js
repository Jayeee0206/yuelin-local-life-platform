let commonURL = "/api";
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 10000;

// Refresh the shared token before each request so login and logout take effect immediately.
let token = sessionStorage.getItem("token");
axios.interceptors.request.use(
  config => {
    token = sessionStorage.getItem("token");
    if (token) config.headers['authorization'] = token;
    return config;
  },
  error => Promise.reject(error)
);

axios.interceptors.response.use(function (response) {
  if (!response.data || response.data.success !== true) {
    return Promise.reject((response.data && response.data.errorMsg) || "请求失败");
  }
  return response.data;
}, function (error) {
  const status = error && error.response ? error.response.status : 0;
  if (status === 403) return Promise.reject("无管理权限，请联系管理员");
  if (status === 401) return Promise.reject("请先登录");
  if (error && error.code === "ECONNABORTED") return Promise.reject("请求超时，请稍后重试");
  if (!status) return Promise.reject("无法连接服务，请确认后端与 Nginx 已启动");
  const serverMessage = error.response && error.response.data && error.response.data.errorMsg;
  return Promise.reject(serverMessage || "服务器异常，请稍后重试");
});

axios.defaults.paramsSerializer = function(params) {
  return Object.keys(params || {})
    .filter(k => params[k] !== undefined && params[k] !== null && params[k] !== "")
    .map(k => encodeURIComponent(k) + "=" + encodeURIComponent(params[k]))
    .join("&");
};

const util = {
  commonURL,
  getUrlParam(name) {
    return new URLSearchParams(window.location.search).get(name) || "";
  },
  errorMessage(error) {
    if (typeof error === "string") return error;
    return (error && error.message) || "操作失败，请稍后重试";
  },
  requireLogin() {
    token = sessionStorage.getItem("token");
    if (token) return true;
    const redirect = location.pathname + location.search;
    location.href = "/login.html?redirect=" + encodeURIComponent(redirect);
    return false;
  },
  plainText(value) {
    return String(value || "")
      .replace(/<br\s*\/?\s*>/gi, "\n")
      .replace(/<[^>]*>/g, "")
      .replace(/&nbsp;/gi, " ")
      .replace(/&amp;/gi, "&")
      .replace(/&lt;/gi, "<")
      .replace(/&gt;/gi, ">");
  },
  formatPrice(val) {
    if (val === null || val === undefined || val === "") return null;
    const cents = Number(val);
    if (!Number.isFinite(cents)) return null;
    return (cents / 100).toFixed(2);
  }
};
