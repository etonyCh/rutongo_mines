package rw.rutongo.mmems.web.ui

@JsFun(
    """
    (status) => {
      let node = document.getElementById('app-status');
      if (!node) {
        node = document.createElement('div');
        node.id = 'app-status';
        node.setAttribute('role', 'status');
        node.setAttribute('aria-live', 'polite');
        node.style.position = 'absolute';
        node.style.width = '1px';
        node.style.height = '1px';
        node.style.overflow = 'hidden';
        node.style.clip = 'rect(0 0 0 0)';
        document.body.appendChild(node);
      }
      node.textContent = status;
    }
    """,
)
external fun publishAutomationStatus(status: String)
