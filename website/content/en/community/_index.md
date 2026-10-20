---
title: Community
menu:
  main:
    weight: 40
---

<p>아래는 원본 기여 이력입니다. 포크의 직접 기여와 구분합니다. <a href="https://github.com/WookJaes/alf.io/commits/main/">포크 이후 커밋</a>을 함께 확인하세요.</p>

<h2 class="text-center mb-5">원본 alf.io에서 이어받은 기여자 이력</h2>
<div class="row">
  <div id="contributors" class="col-8 offset-2 row">

  </div>
</div>

<div class="d-none col-xs-6 col-md-4" id="contributors-template">
    <div class="media mb-4">
        <a href="#" class="contributor-link">
            <img class="media-object img-thumbnail contributor-avatar" style="max-height: 120px" src="" alt="">
        </a>
        <div class="media-body ml-2">
            <h4 class="media-heading"><span class="contributor-name"></span></h4>
            <p><i class="fas fa-code-branch"></i> contributions: <span class="contributor-contributions"></span></p>
            <p><i class="fab fa-github"></i> <a class="contributor-link"><span class="contributor-nickname"></span></a></p>
        </div>
    </div>
</div>

<script>
    $(document).ready(function(){
        $.getJSON('https://api.github.com/repos/alfio-event/alf.io/contributors', function(list) {
            list.forEach(function(contributor) {
                var element = $('#contributors-template').clone();
                element.find('.contributor-link').attr('href', contributor.html_url);
                element.find('.contributor-avatar').attr('src', contributor.avatar_url);
                element.find('.contributor-nickname').html(contributor.login);
                element.find('.contributor-contributions').html(contributor.contributions);
                element.appendTo('#contributors');
                $.getJSON(contributor.url, function(details) {
                    var name = details.name && details.name.length > 0 ? details.name : contributor.login;
                    element.find('.contributor-name').html(name);
                    element.find('.contributor-avatar').attr('alt', name);
                    element.removeClass('d-none');
                });
            });
        });
    });
</script>