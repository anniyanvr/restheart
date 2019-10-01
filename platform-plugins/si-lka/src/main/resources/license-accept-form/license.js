(function (window, document, undefined) {
  window.onload = init;

  function init() {
    var licenseChecker = document.getElementById('licenseChecker');
    var specialTermsChecker = document.getElementById('specialTermsChecker');
    var acceptBtn = document.getElementById('acceptBtn');
    var acceptSection = document.getElementById('acceptSection');
    var lk = document.getElementById('lk');
    var doneSaveLk = document.getElementById('doneSaveLk');
    var lkSection = document.getElementById('lkSection');
    var saveKeyBtn = document.getElementById('saveKeyBtn');
    var errorKeyMsg = document.getElementById('errorKeyMsg');
    var errorMsg = document.getElementById('errorMsg');
    var doneMsg = document.getElementById('doneMsg');

    licenseChecker.onchange = function () {
      acceptBtn.disabled = !this.checked || !specialTermsChecker.checked;
    };

    specialTermsChecker.onchange = function () {
      acceptBtn.disabled = !this.checked || !licenseChecker.checked;
    };

    fetch('text')
            .then(response => response.text())
            .then(body => {
              document.getElementById('TaC').innerHTML = body;
            });

    fetch('save')
            .then(response => response.status)
            .then(status => {
              if (status === 200) {
                this.lkSection.classList.remove("d-none");
                this.acceptSection.classList.add("d-none")
              } else {
                this.lkSection.classList.add("d-none");
                this.acceptSection.classList.remove("d-none");
              }
            });

    acceptBtn.onclick = function () {
      errorMsg.classList.add('d-nome');

      specialTermsChecker.disabled = true;
      licenseChecker.disabled = true;
      acceptBtn.disabled = true;

      fetch('accept', {method: 'POST'})
              .then(response => {
                if (response.status === 200) {
                  doneMsg.classList.remove('d-none');
                  doneSaveLk.classList.add('d-none');
                } else {
                  errorMsg.classList.remove('d-none');

                  specialTermsChecker.disabled = false;
                  licenseChecker.disabled = false;
                  acceptBtn.disabled = false;
                }
              })
              .catch(error => {
                errorMsg.classList.remove('d-none');

                specialTermsChecker.disabled = false;
                licenseChecker.disabled = false;
                acceptBtn.disabled = false;
              });
    }

    saveKeyBtn.onclick = function () {
      errorMsg.classList.add('d-nome');

      fetch('save', {method: 'POST', body: lk.value})
              .then(response => {
                if (response.status === 200) {
                  errorKeyMsg.classList.add('d-none');
                  lkSection.classList.add('d-none');

                  doneSaveLk.classList.remove('d-none');
                  acceptSection.classList.remove("d-none");
                } else {
                  errorKeyMsg.classList.remove('d-none');
                  lkSection.classList.remove('d-none');

                  doneSaveLk.classList.add('d-none');
                  acceptSection.classList.add("d-none");
                }
              })
              .catch(error => {
                errorKeyMsg.classList.remove('d-none');
                lkSection.classList.remove('d-none');

                doneSaveLk.classList.add('d-none');
                acceptSection.classList.add("d-none");
              });
    }
  }
})(window, document, undefined);



